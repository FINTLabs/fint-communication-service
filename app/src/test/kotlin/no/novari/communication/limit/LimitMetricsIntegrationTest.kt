package no.novari.communication.limit

import io.micrometer.core.instrument.MeterRegistry
import no.novari.communication.ControllableDispatcher
import no.novari.communication.IntegrationTest
import no.novari.communication.MutableClock
import no.novari.communication.blocklist.RecipientBlockedException
import no.novari.communication.message.MessageMetrics
import no.novari.communication.message.MessageService
import no.novari.communication.message.domain.EmailPayload
import no.novari.communication.message.domain.MessageId
import no.novari.communication.model.Tenant
import no.novari.communication.recipient.RecipientHash
import no.novari.communication.recipient.RecipientHasher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

@IntegrationTest
@TestPropertySource(
    properties = [
        "communication.limits.recipient.per-hour=2",
        "communication.limits.recipient.per-day=3",
        "communication.limits.tenant.default.per-hour=4",
        "communication.limits.tenant.default.per-day=6",
        "communication.limits.tenant.overrides.AGDER.per-hour=6",
        "communication.limits.tenant.overrides.AGDER.per-day=8",
        "communication.limits.total.per-hour=12",
        "communication.limits.total.per-day=16",
    ],
)
class LimitMetricsIntegrationTest {
    @Autowired
    lateinit var messageService: MessageService

    @Autowired
    lateinit var usageMetrics: LimitUsageMetrics

    @Autowired
    lateinit var repository: SendUsageRepository

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var dispatcher: ControllableDispatcher

    @Autowired
    lateinit var hasher: RecipientHasher

    @Autowired
    lateinit var jdbcClient: JdbcClient

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    private val recipientNumber = AtomicInteger()

    @BeforeEach
    fun setUp() {
        jdbcClient.sql("DELETE FROM send_usage").update()
        jdbcClient.sql("DELETE FROM recipient_blocklist").update()
        clock.set(START)
    }

    @AfterEach
    fun tearDown() {
        dispatcher.failure = null
    }

    @Test
    fun `a rejection on the recipient limit is counted with limit and tenant`() {
        val before = rejected(LimitType.RECIPIENT, Tenant.ROGALAND)

        repeat(2) { send(Tenant.ROGALAND, ADDRESS) }
        assertThatThrownBy { send(Tenant.ROGALAND, ADDRESS) }.isInstanceOf(LimitExceededException::class.java)

        assertThat(rejected(LimitType.RECIPIENT, Tenant.ROGALAND)).isEqualTo(before + 1)
    }

    @Test
    fun `a rejection on the tenant limit is counted for the rejected tenant`() {
        val before = rejected(LimitType.TENANT, Tenant.TROMS)

        repeat(4) { send(Tenant.TROMS) }
        assertThatThrownBy { send(Tenant.TROMS) }.isInstanceOf(LimitExceededException::class.java)
        assertThatThrownBy { send(Tenant.TROMS) }.isInstanceOf(LimitExceededException::class.java)

        assertThat(rejected(LimitType.TENANT, Tenant.TROMS)).isEqualTo(before + 2)
    }

    @Test
    fun `a rejection on the total limit is counted for the tenant that was rejected`() {
        val before = rejected(LimitType.TOTAL, Tenant.NOVARI)

        listOf(Tenant.ROGALAND, Tenant.AGDER, Tenant.VESTLAND).forEach { tenant -> repeat(4) { send(tenant) } }
        assertThatThrownBy { send(Tenant.NOVARI) }.isInstanceOf(LimitExceededException::class.java)

        assertThat(rejected(LimitType.TOTAL, Tenant.NOVARI)).isEqualTo(before + 1)
    }

    @Test
    fun `the rejection counter has only limit and tenant as tags`() {
        repeat(2) { send(Tenant.ROGALAND, ADDRESS) }
        assertThatThrownBy { send(Tenant.ROGALAND, ADDRESS) }

        assertThat(meterRegistry.find(DatabaseSendLimiter.REJECTED_METRIC).meters())
            .isNotEmpty()
            .allSatisfy { meter -> assertThat(meter.id.tags.map { it.key }).containsExactly("limit", "tenant") }
    }

    @Test
    fun `only accepted messages are counted as accepted`() {
        val before = accepted(Tenant.VESTFOLD)

        repeat(2) { send(Tenant.VESTFOLD, ADDRESS) }
        assertThatThrownBy { send(Tenant.VESTFOLD, ADDRESS) }.isInstanceOf(LimitExceededException::class.java)

        jdbcClient
            .sql(
                """
                INSERT INTO recipient_blocklist (recipient_hash, reason, source, expires_at)
                VALUES (:hash, 'OPT_OUT', 'MANUAL', NULL)
                """.trimIndent(),
            ).param("hash", hasher.hash(BLOCKED_ADDRESS).value)
            .update()
        assertThatThrownBy { send(Tenant.VESTFOLD, BLOCKED_ADDRESS) }
            .isInstanceOf(RecipientBlockedException::class.java)

        dispatcher.failure = IllegalStateException("dispatch feilet")
        assertThatThrownBy { send(Tenant.VESTFOLD) }.isInstanceOf(IllegalStateException::class.java)

        assertThat(accepted(Tenant.VESTFOLD)).isEqualTo(before + 2)
        assertThat(meterRegistry.find(MessageMetrics.ACCEPTED_METRIC).meters())
            .allSatisfy { meter -> assertThat(meter.id.tags.map { it.key }).containsExactly("channel", "tenant") }
    }

    @Test
    fun `usage per tenant and in total matches send_usage within each window`() {
        val now = START + Duration.ofDays(2)
        record(Tenant.AGDER, now - Duration.ofMinutes(1), now - Duration.ofMinutes(30), now - Duration.ofMinutes(59))
        record(Tenant.AGDER, now - Duration.ofHours(1), now - Duration.ofHours(5))
        record(Tenant.AGDER, now - Duration.ofDays(1), now - Duration.ofHours(25))
        record(Tenant.ROGALAND, now - Duration.ofMinutes(10), now - Duration.ofHours(23))
        clock.set(now)

        usageMetrics.refresh()

        assertThat(tenantUsage(Tenant.AGDER, "hour")).isCloseTo(3.0 / 6, within(1e-9))
        assertThat(tenantUsage(Tenant.AGDER, "day")).isCloseTo(5.0 / 8, within(1e-9))
        assertThat(tenantUsage(Tenant.ROGALAND, "hour")).isCloseTo(1.0 / 4, within(1e-9))
        assertThat(tenantUsage(Tenant.ROGALAND, "day")).isCloseTo(2.0 / 6, within(1e-9))
        assertThat(tenantUsage(Tenant.TROMS, "hour")).isZero()
        assertThat(tenantUsage(Tenant.TROMS, "day")).isZero()
        assertThat(totalUsage("hour")).isCloseTo(4.0 / 12, within(1e-9))
        assertThat(totalUsage("day")).isCloseTo(7.0 / 16, within(1e-9))
    }

    @Test
    fun `usage drops when messages leave the window`() {
        repeat(3) { send(Tenant.ROGALAND) }
        usageMetrics.refresh()
        assertThat(tenantUsage(Tenant.ROGALAND, "hour")).isCloseTo(3.0 / 4, within(1e-9))

        clock.advance(Duration.ofHours(1))
        usageMetrics.refresh()

        assertThat(tenantUsage(Tenant.ROGALAND, "hour")).isZero()
        assertThat(tenantUsage(Tenant.ROGALAND, "day")).isCloseTo(3.0 / 6, within(1e-9))
    }

    @Test
    fun `every tenant is reported`() {
        usageMetrics.refresh()

        assertThat(meterRegistry.find(LimitUsageMetrics.TENANT_USAGE_METRIC).gauges().map { it.id.getTag("tenant") })
            .containsExactlyInAnyOrderElementsOf(Tenant.entries.flatMap { listOf(it.name, it.name) })
    }

    @Test
    fun `the prometheus scrape has the new metrics`() {
        send(Tenant.ROGALAND, ADDRESS)
        send(Tenant.ROGALAND, ADDRESS)
        assertThatThrownBy { send(Tenant.ROGALAND, ADDRESS) }
        usageMetrics.refresh()

        val scrape =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .build()
                .get("/actuator/prometheus")
                .andExpect { status { isOk() } }
                .andReturn()
                .response.contentAsString

        assertThat(scrape)
            .containsPattern("""communication_limit_rejected_total\{limit="mottaker",tenant="ROGALAND"}""")
            .containsPattern("""communication_message_accepted_total\{channel="EMAIL",tenant="ROGALAND"}""")
            .containsPattern("""communication_limit_tenant_usage_ratio\{tenant="ROGALAND",window="hour"} 0\.5""")
            .containsPattern("""communication_limit_total_usage_ratio\{window="day"} 0\.125""")
    }

    private fun send(
        tenant: Tenant,
        to: String = "mottaker${recipientNumber.incrementAndGet()}@rogfk.no",
    ) = messageService.receive(
        tenant,
        EmailPayload(templateId = "team/varsel", to = to, subject = "Emne", body = "Innhold"),
    )

    private fun record(
        tenant: Tenant,
        vararg sentAt: Instant,
    ) {
        sentAt.forEach {
            repository.record(MessageId.generate(), RecipientHash("0".repeat(64)), tenant, it)
        }
    }

    private fun rejected(
        type: LimitType,
        tenant: Tenant,
    ): Double =
        meterRegistry
            .find(DatabaseSendLimiter.REJECTED_METRIC)
            .tags("limit", type.value, "tenant", tenant.name)
            .counter()
            ?.count() ?: 0.0

    private fun accepted(tenant: Tenant): Double =
        meterRegistry
            .find(MessageMetrics.ACCEPTED_METRIC)
            .tags("tenant", tenant.name, "channel", "EMAIL")
            .counter()
            ?.count() ?: 0.0

    private fun tenantUsage(
        tenant: Tenant,
        window: String,
    ): Double =
        meterRegistry
            .get(LimitUsageMetrics.TENANT_USAGE_METRIC)
            .tags("tenant", tenant.name, "window", window)
            .gauge()
            .value()

    private fun totalUsage(window: String): Double =
        meterRegistry
            .get(LimitUsageMetrics.TOTAL_USAGE_METRIC)
            .tags("window", window)
            .gauge()
            .value()

    private companion object {
        const val ADDRESS = "metrikk.mottaker@rogfk.no"
        const val BLOCKED_ADDRESS = "metrikk.avmeldt@rogfk.no"
        val START: Instant = Instant.parse("2026-10-08T08:00:00Z")
    }
}
