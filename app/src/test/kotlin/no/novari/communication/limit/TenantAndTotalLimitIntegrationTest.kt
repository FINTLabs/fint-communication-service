package no.novari.communication.limit

import no.novari.communication.IntegrationTest
import no.novari.communication.MutableClock
import no.novari.communication.api.MessageController
import no.novari.communication.message.MessageService
import no.novari.communication.message.domain.EmailPayload
import no.novari.communication.model.Tenant
import no.novari.communication.recipient.RecipientHasher
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

@IntegrationTest
@TestPropertySource(
    properties = [
        "communication.limits.tenant.default.per-hour=4",
        "communication.limits.tenant.default.per-day=6",
        "communication.limits.tenant.overrides.AGDER.per-hour=6",
        "communication.limits.tenant.overrides.AGDER.per-day=8",
        "communication.limits.total.per-hour=12",
        "communication.limits.total.per-day=16",
    ],
)
@ExtendWith(OutputCaptureExtension::class)
class TenantAndTotalLimitIntegrationTest {
    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var messageService: MessageService

    @Autowired
    lateinit var hasher: RecipientHasher

    @Autowired
    lateinit var jdbcClient: JdbcClient

    @Autowired
    lateinit var dataSource: DataSource

    private lateinit var mockMvc: MockMvc
    private val recipientNumber = AtomicInteger()

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        jdbcClient.sql("DELETE FROM send_usage").update()
        clock.set(START)
    }

    @Test
    fun `a tenant reaching its hourly limit is rejected while other tenants are not`() {
        repeat(4) { minute ->
            at(Duration.ofMinutes(minute.toLong()))
            send(Tenant.ROGALAND).andExpect { status { isAccepted() } }
        }

        at(Duration.ofMinutes(10))
        send(Tenant.ROGALAND).andExpect {
            status { isTooManyRequests() }
            header { string(HttpHeaders.RETRY_AFTER, "3000") }
            jsonPath("$.limit") { value("tenant") }
            jsonPath("$.detail") { value("Tenanten har sendt for mange meldinger. Prøv igjen senere.") }
        }
        send(Tenant.TROMS).andExpect { status { isAccepted() } }

        at(Duration.ofHours(1))
        send(Tenant.ROGALAND).andExpect { status { isAccepted() } }
    }

    @Test
    fun `a tenant reaching its daily limit is rejected until the first message leaves the window`() {
        repeat(6) { hour ->
            at(Duration.ofHours(2L * hour))
            send(Tenant.ROGALAND).andExpect { status { isAccepted() } }
        }

        at(Duration.ofHours(12))
        send(Tenant.ROGALAND).andExpect {
            status { isTooManyRequests() }
            header { string(HttpHeaders.RETRY_AFTER, "43200") }
            jsonPath("$.limit") { value("tenant") }
        }

        at(Duration.ofDays(1))
        send(Tenant.ROGALAND).andExpect { status { isAccepted() } }
    }

    @Test
    fun `an override applies to its tenant and the default to the others`() {
        repeat(6) { send(Tenant.AGDER).andExpect { status { isAccepted() } } }
        send(Tenant.AGDER).andExpect {
            status { isTooManyRequests() }
            jsonPath("$.limit") { value("tenant") }
        }

        repeat(4) { send(Tenant.ROGALAND).andExpect { status { isAccepted() } } }
        send(Tenant.ROGALAND).andExpect {
            status { isTooManyRequests() }
            jsonPath("$.limit") { value("tenant") }
        }
    }

    @Test
    fun `reaching the hourly total limit rejects every tenant`() {
        val tenants = listOf(Tenant.ROGALAND, Tenant.AGDER, Tenant.TROMS, Tenant.VESTLAND)
        repeat(12) { minute ->
            at(Duration.ofMinutes(minute.toLong()))
            send(tenants[minute % tenants.size]).andExpect { status { isAccepted() } }
        }

        at(Duration.ofMinutes(20))
        listOf(Tenant.NOVARI, Tenant.OSLO_BYMILJOETATEN).forEach { tenant ->
            send(tenant).andExpect {
                status { isTooManyRequests() }
                header { string(HttpHeaders.RETRY_AFTER, "2400") }
                jsonPath("$.limit") { value("total") }
                jsonPath("$.detail") { value("Tjenesten har sendt for mange meldinger. Prøv igjen senere.") }
            }
        }

        at(Duration.ofHours(1))
        send(Tenant.NOVARI).andExpect { status { isAccepted() } }
    }

    @Test
    fun `reaching the daily total limit rejects every tenant until the first message leaves the window`() {
        val tenants = listOf(Tenant.ROGALAND, Tenant.AGDER, Tenant.TROMS, Tenant.VESTLAND)
        repeat(16) { hour ->
            at(Duration.ofHours(hour.toLong()))
            send(tenants[hour % tenants.size]).andExpect { status { isAccepted() } }
        }

        at(Duration.ofHours(16))
        send(Tenant.NOVARI).andExpect {
            status { isTooManyRequests() }
            header { string(HttpHeaders.RETRY_AFTER, "28800") }
            jsonPath("$.limit") { value("total") }
        }

        at(Duration.ofDays(1))
        send(Tenant.NOVARI).andExpect { status { isAccepted() } }
    }

    @Test
    fun `the tenant limit is reported when it gives the longest wait`() {
        repeat(4) { send(Tenant.AGDER) }
        repeat(4) { send(Tenant.TROMS) }
        at(Duration.ofMinutes(40))
        repeat(4) { send(Tenant.ROGALAND) }

        at(Duration.ofMinutes(50))
        send(Tenant.ROGALAND).andExpect {
            status { isTooManyRequests() }
            header { string(HttpHeaders.RETRY_AFTER, "3000") }
            jsonPath("$.limit") { value("tenant") }
        }
    }

    @Test
    fun `the total limit is reported when it gives the longest wait`() {
        val tenants = listOf(Tenant.AGDER, Tenant.TROMS, Tenant.VESTLAND)
        repeat(12) { hour ->
            at(Duration.ofHours(hour.toLong()))
            send(tenants[hour % tenants.size]).andExpect { status { isAccepted() } }
        }
        repeat(4) { minute ->
            at(Duration.ofHours(12).plusMinutes(minute.toLong()))
            send(Tenant.ROGALAND).andExpect { status { isAccepted() } }
        }

        at(Duration.ofHours(12).plusMinutes(10))
        send(Tenant.ROGALAND).andExpect {
            status { isTooManyRequests() }
            header { string(HttpHeaders.RETRY_AFTER, "42600") }
            jsonPath("$.limit") { value("total") }
        }
    }

    @Test
    fun `usage is only recorded when every limit passes`() {
        repeat(4) { send(Tenant.ROGALAND) }
        send(Tenant.ROGALAND).andExpect { status { isTooManyRequests() } }
        assertThat(usageRows()).isEqualTo(4)

        repeat(8) { i -> send(if (i < 4) Tenant.TROMS else Tenant.VESTLAND) }
        send(Tenant.NOVARI).andExpect { status { isTooManyRequests() } }
        assertThat(usageRows()).isEqualTo(12)
    }

    @Test
    fun `parallel requests cannot exceed the tenant limit`() {
        val results = sendInParallel(List(20) { Tenant.ROGALAND })

        assertThat(results.count { it.isSuccess }).isEqualTo(4)
        assertThat(results.mapNotNull { it.exceptionOrNull() })
            .hasSize(16)
            .allMatch { it is LimitExceededException && it.type == LimitType.TENANT }
        assertThat(usageRows()).isEqualTo(4)
    }

    @Test
    fun `parallel requests cannot exceed the total limit`() {
        val tenants = listOf(Tenant.ROGALAND, Tenant.TROMS, Tenant.VESTLAND, Tenant.NOVARI, Tenant.TELEMARK)
        val results = sendInParallel(tenants.flatMap { tenant -> List(4) { tenant } })

        assertThat(results.count { it.isSuccess }).isEqualTo(12)
        assertThat(results.mapNotNull { it.exceptionOrNull() })
            .hasSize(8)
            .allMatch { it is LimitExceededException }
        assertThat(usageRows()).isEqualTo(12)
    }

    @Test
    fun `the total lock not being granted in time gives service unavailable`() {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(1, 0)") }
            try {
                send(Tenant.ROGALAND).andExpect {
                    status { isServiceUnavailable() }
                    jsonPath("$.detail") { value("Tjenesten er midlertidig utilgjengelig") }
                }
            } finally {
                connection.rollback()
            }
        }

        assertThat(usageRows()).isZero()
    }

    @Test
    fun `neither the response nor the log contains the address or its hash`(output: CapturedOutput) {
        repeat(4) { send(Tenant.ROGALAND, to = ADDRESS) }

        send(Tenant.ROGALAND, to = ADDRESS).andExpect {
            status { isTooManyRequests() }
            content { string(not(containsString(ADDRESS))) }
            content { string(not(containsString(hasher.hash(ADDRESS).value))) }
        }

        assertThat(output.all)
            .contains("Grense overskredet grensetype=tenant tenant=ROGALAND")
            .doesNotContainIgnoringCase(ADDRESS)
            .doesNotContain(hasher.hash(ADDRESS).value)
    }

    private fun at(offset: Duration) = clock.set(START.plus(offset))

    private fun nextRecipient(): String = "mottaker${recipientNumber.incrementAndGet()}@rogfk.no"

    private fun send(
        tenant: Tenant,
        to: String = nextRecipient(),
    ): ResultActionsDsl =
        mockMvc.post(MessageController.MESSAGES_PATH) {
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "tenant": "${tenant.name}",
                  "message": {
                    "channel": "EMAIL",
                    "to": "$to",
                    "templateId": "team/varsel",
                    "variables": { "saksnummer": "2026-1" },
                    "lists": { "rader": [ { "navn": "ACOS" } ] }
                  }
                }
                """.trimIndent()
        }

    private fun sendInParallel(tenants: List<Tenant>): List<Result<*>> {
        val ready = CountDownLatch(tenants.size)
        val go = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(tenants.size)
        return try {
            tenants
                .map { tenant ->
                    val to = nextRecipient()
                    executor.submit(
                        Callable {
                            ready.countDown()
                            go.await()
                            runCatching {
                                messageService.receive(
                                    tenant,
                                    EmailPayload(
                                        templateId = "team/varsel",
                                        to = to,
                                        subject = "Emne",
                                        body = "Innhold",
                                    ),
                                )
                            }
                        },
                    )
                }.also {
                    ready.await()
                    go.countDown()
                }.map { it.get() }
        } finally {
            executor.shutdown()
        }
    }

    private fun usageRows(): Long = jdbcClient.sql("SELECT count(*) FROM send_usage").query(Long::class.java).single()

    private companion object {
        const val ADDRESS = "ola@rogfk.no"
        val START: Instant = Instant.parse("2026-10-08T08:00:00Z")
    }
}
