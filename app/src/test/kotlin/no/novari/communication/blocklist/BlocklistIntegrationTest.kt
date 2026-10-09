package no.novari.communication.blocklist

import io.micrometer.core.instrument.MeterRegistry
import no.novari.communication.IntegrationTest
import no.novari.communication.MutableClock
import no.novari.communication.api.MessageController
import no.novari.communication.message.MessageService
import no.novari.communication.message.domain.EmailPayload
import no.novari.communication.model.Tenant
import no.novari.communication.recipient.RecipientHasher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@IntegrationTest
@ExtendWith(OutputCaptureExtension::class)
class BlocklistIntegrationTest {
    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var cleaner: BlocklistCleaner

    @Autowired
    lateinit var hasher: RecipientHasher

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    @Autowired
    lateinit var messageService: MessageService

    @Autowired
    lateinit var jdbcClient: JdbcClient

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        jdbcClient.sql("DELETE FROM send_usage").update()
        jdbcClient.sql("DELETE FROM recipient_blocklist").update()
        clock.set(START)
    }

    @AfterEach
    fun removeBlocks() {
        jdbcClient.sql("DELETE FROM recipient_blocklist").update()
    }

    @Test
    fun `an opted out recipient is always rejected`() {
        block(OPT_OUT, expiresAt = null)

        send().andExpect {
            status { isUnprocessableContent() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.detail") { value("Mottakeren kan ikke motta e-post fra tjenesten.") }
        }
        clock.set(START.plus(Duration.ofDays(3650)))
        send().andExpect { status { isUnprocessableContent() } }
    }

    @Test
    fun `a hard bounced recipient is rejected until the block expires`() {
        block(HARD_BOUNCE, expiresAt = START.plus(BOUNCE_BLOCK))

        clock.set(START.plus(BOUNCE_BLOCK).minusSeconds(1))
        send().andExpect { status { isUnprocessableContent() } }

        clock.set(START.plus(BOUNCE_BLOCK))
        send().andExpect { status { isAccepted() } }
    }

    @Test
    fun `case and surrounding whitespace do not escape the block`() {
        block(OPT_OUT, expiresAt = null, address = " Ola@RogFK.no ")

        listOf("ola@rogfk.no", "OLA@ROGFK.NO", "ola@Rogfk.No").forEach { variant ->
            send(to = variant).andExpect { status { isUnprocessableContent() } }
        }
        assertThatThrownBy {
            messageService.receive(
                Tenant.ROGALAND,
                EmailPayload(templateId = "team/varsel", to = "  OLA@rogfk.no\t", subject = "Emne", body = "Innhold"),
            )
        }.isInstanceOf(RecipientBlockedException::class.java)
    }

    @Test
    fun `the block applies to every tenant`() {
        block(OPT_OUT, expiresAt = null)

        Tenant.entries.forEach { tenant ->
            send(tenant = tenant).andExpect { status { isUnprocessableContent() } }
        }
    }

    @Test
    fun `other recipients are not affected`() {
        block(OPT_OUT, expiresAt = null)

        send(to = "kari@rogfk.no").andExpect { status { isAccepted() } }
    }

    @Test
    fun `blocked messages are not counted against the limits`() {
        block(OPT_OUT, expiresAt = null)
        repeat(RECIPIENT_PER_HOUR + 5) { send().andExpect { status { isUnprocessableContent() } } }

        assertThat(usageRows()).isZero()
        jdbcClient.sql("DELETE FROM recipient_blocklist").update()
        repeat(RECIPIENT_PER_HOUR) { send().andExpect { status { isAccepted() } } }
    }

    @Test
    fun `rejections are counted per tenant without other tags`() {
        block(OPT_OUT, expiresAt = null)
        val before = rejectedCount(Tenant.AGDER)

        send(tenant = Tenant.AGDER)
        send(tenant = Tenant.AGDER)

        assertThat(rejectedCount(Tenant.AGDER)).isEqualTo(before + 2)
        assertThat(meterRegistry.find(DatabaseRecipientBlocklist.REJECTED_METRIC).meters())
            .allSatisfy { meter -> assertThat(meter.id.tags.map { it.key }).containsExactly("tenant") }
    }

    @Test
    fun `neither the response nor the log contains the address, the hash or the reason`(output: CapturedOutput) {
        block(HARD_BOUNCE, expiresAt = START.plus(BOUNCE_BLOCK))
        val hash = hasher.hash(ADDRESS).value

        send().andExpect {
            status { isUnprocessableContent() }
            content { string(not(containsString(ADDRESS))) }
            content { string(not(containsString(hash))) }
            content { string(not(containsString(HARD_BOUNCE))) }
        }

        assertThat(output.all)
            .containsPattern("Mottaker blokkert tenant=ROGALAND id=[0-9a-f-]{36}")
            .doesNotContainIgnoringCase(ADDRESS)
            .doesNotContain(hash)
    }

    @Test
    fun `the cleaner deletes expired blocks and keeps opt-outs and active bounces`() {
        block(OPT_OUT, expiresAt = null, address = "a@rogfk.no")
        block(HARD_BOUNCE, expiresAt = START.minusSeconds(1), address = "b@rogfk.no")
        block(HARD_BOUNCE, expiresAt = START, address = "c@rogfk.no")
        block(HARD_BOUNCE, expiresAt = START.plusSeconds(1), address = "d@rogfk.no")

        val deleted = cleaner.deleteExpired(START)

        assertThat(deleted).isEqualTo(2)
        assertThat(blockedHashes()).containsExactlyInAnyOrder(hashOf("a@rogfk.no"), hashOf("d@rogfk.no"))
    }

    @Test
    fun `the operations routine adds an opt-out once and removes it without touching a bounce`() {
        clock.set(Instant.now())
        val hash = hasher.hash(ADDRESS).value
        runRoutine(ADD_OPT_OUT, hash)
        runRoutine(ADD_OPT_OUT, hash)
        runRoutine(ADD_HARD_BOUNCE, hash)

        assertThat(reasons(hash)).containsExactlyInAnyOrder(OPT_OUT, HARD_BOUNCE)
        send().andExpect { status { isUnprocessableContent() } }

        runRoutine(REMOVE_OPT_OUT, hash)
        assertThat(reasons(hash)).containsExactly(HARD_BOUNCE)
        send().andExpect { status { isUnprocessableContent() } }

        runRoutine(REMOVE_ALL, hash)
        assertThat(reasons(hash)).isEmpty()
        send().andExpect { status { isAccepted() } }
    }

    @Test
    fun `a new hard bounce extends an existing block but never shortens it`() {
        val hash = hasher.hash(ADDRESS).value
        val soon = Instant.now().plus(Duration.ofDays(1))
        val later = Instant.now().plus(Duration.ofDays(100))

        block(HARD_BOUNCE, expiresAt = soon)
        runRoutine(ADD_HARD_BOUNCE, hash)
        assertThat(expiresAt(hash)).isAfter(Instant.now().plus(BOUNCE_BLOCK).minus(Duration.ofMinutes(1)))

        jdbcClient.sql("DELETE FROM recipient_blocklist").update()
        block(HARD_BOUNCE, expiresAt = later)
        runRoutine(ADD_HARD_BOUNCE, hash)
        assertThat(expiresAt(hash)).isEqualTo(later)
    }

    @Test
    fun `the table rejects an opt-out with expiry and a hard bounce without`() {
        assertThatThrownBy { block(OPT_OUT, expiresAt = START) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { block(HARD_BOUNCE, expiresAt = null) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    private fun block(
        reason: String,
        expiresAt: Instant?,
        address: String = ADDRESS,
    ) {
        jdbcClient
            .sql(
                """
                INSERT INTO recipient_blocklist (recipient_hash, reason, source, created_at, expires_at)
                VALUES (:hash, :reason, 'MANUAL', :createdAt, :expiresAt)
                """.trimIndent(),
            ).param("hash", hashOf(address))
            .param("reason", reason)
            .param("createdAt", START.toOffsetDateTime())
            .param("expiresAt", expiresAt?.toOffsetDateTime())
            .update()
    }

    private fun runRoutine(
        sql: String,
        hash: String,
    ) {
        jdbcClient.sql(sql.replace("'<hash>'", ":hash")).param("hash", hash).update()
    }

    private fun reasons(hash: String): List<String> =
        jdbcClient
            .sql("SELECT reason FROM recipient_blocklist WHERE recipient_hash = :hash")
            .param("hash", hash)
            .query { rs, _ -> rs.getString("reason") }
            .list()

    private fun expiresAt(hash: String): Instant =
        jdbcClient
            .sql("SELECT expires_at FROM recipient_blocklist WHERE recipient_hash = :hash")
            .param("hash", hash)
            .query { rs, _ -> rs.getObject("expires_at", OffsetDateTime::class.java).toInstant() }
            .single()

    private fun blockedHashes(): List<String> =
        jdbcClient
            .sql("SELECT recipient_hash FROM recipient_blocklist")
            .query { rs, _ -> rs.getString("recipient_hash") }
            .list()

    private fun rejectedCount(tenant: Tenant): Double =
        meterRegistry
            .find(DatabaseRecipientBlocklist.REJECTED_METRIC)
            .tag("tenant", tenant.name)
            .counter()
            ?.count() ?: 0.0

    private fun usageRows(): Long = jdbcClient.sql("SELECT count(*) FROM send_usage").query(Long::class.java).single()

    private fun hashOf(address: String): String = hasher.hash(address).value

    private fun send(
        to: String = ADDRESS,
        tenant: Tenant = Tenant.ROGALAND,
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

    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

    private companion object {
        const val ADDRESS = "ola@rogfk.no"
        const val OPT_OUT = "OPT_OUT"
        const val HARD_BOUNCE = "HARD_BOUNCE"
        const val RECIPIENT_PER_HOUR = 10
        val BOUNCE_BLOCK: Duration = Duration.ofDays(30)
        val START: Instant = Instant.parse("2026-10-08T08:00:00Z")

        const val ADD_OPT_OUT = """
INSERT INTO recipient_blocklist (recipient_hash, reason, source, expires_at)
VALUES ('<hash>', 'OPT_OUT', 'MANUAL', NULL)
ON CONFLICT (recipient_hash, reason) DO NOTHING;
"""

        const val ADD_HARD_BOUNCE = """
INSERT INTO recipient_blocklist (recipient_hash, reason, source, expires_at)
VALUES ('<hash>', 'HARD_BOUNCE', 'MANUAL', now() + interval '30 days')
ON CONFLICT (recipient_hash, reason)
DO UPDATE SET expires_at = GREATEST(recipient_blocklist.expires_at, EXCLUDED.expires_at);
"""

        const val REMOVE_OPT_OUT = """
DELETE FROM recipient_blocklist WHERE recipient_hash = '<hash>' AND reason = 'OPT_OUT';
"""

        const val REMOVE_ALL = """
DELETE FROM recipient_blocklist WHERE recipient_hash = '<hash>';
"""
    }
}
