package no.novari.communication.recipient

import io.micrometer.core.instrument.MeterRegistry
import no.novari.communication.IntegrationTest
import no.novari.communication.TEST_RECIPIENT_HASHING_KEY
import no.novari.communication.limit.LimitExceededException
import no.novari.communication.message.MessageService
import no.novari.communication.message.domain.EmailPayload
import no.novari.communication.model.Tenant
import no.novari.communication.retention.RetentionCleanupJob
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.jdbc.core.simple.JdbcClient

@IntegrationTest
@ExtendWith(OutputCaptureExtension::class)
class RecipientPrivacyTest {
    @Autowired
    lateinit var hasher: RecipientHasher

    @Autowired
    lateinit var cleanupJob: RetentionCleanupJob

    @Autowired
    lateinit var jdbcClient: JdbcClient

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    @Autowired
    lateinit var messageService: MessageService

    @BeforeEach
    fun sendUntilTheLimitIsExceeded() {
        jdbcClient.sql("DELETE FROM send_usage").update()
        repeat(RECIPIENT_PER_HOUR) { send() }
        assertThatThrownBy { send() }.isInstanceOf(LimitExceededException::class.java)
    }

    @Test
    fun `neither the address, the hash nor the key ends up in logs`(output: CapturedOutput) {
        val hash = hasher.hash(ADDRESS)
        cleanupJob.deleteExpiredRows()

        assertThat(output.all)
            .doesNotContainIgnoringCase(ADDRESS.trim())
            .doesNotContain(hash.value)
            .doesNotContain(TEST_RECIPIENT_HASHING_KEY)
    }

    @Test
    fun `no text column in the database contains a plaintext address`() {
        assertThat(textColumns()).contains("send_usage" to "recipient_hash", "send_usage" to "tenant")

        val columnsContainingAddress =
            textColumns().filter { (table, column) ->
                jdbcClient
                    .sql("""SELECT count(*) FROM "$table" WHERE "$column"::text ILIKE :address""")
                    .param("address", "%${ADDRESS.trim()}%")
                    .query(Long::class.java)
                    .single() > 0
            }

        assertThat(columnsContainingAddress).isEmpty()
    }

    @Test
    fun `no metric tag contains the address`() {
        val tagValues = meterRegistry.meters.flatMap { meter -> meter.id.tags.map { it.value } }

        assertThat(tagValues).noneMatch { it.contains(ADDRESS.trim(), ignoreCase = true) }
    }

    private fun textColumns(): List<Pair<String, String>> =
        jdbcClient
            .sql(
                """
                SELECT table_name, column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND data_type IN ('text', 'character varying', 'character', 'json', 'jsonb')
                """.trimIndent(),
            ).query { rs, _ -> rs.getString("table_name") to rs.getString("column_name") }
            .list()

    private fun send() =
        messageService.receive(
            Tenant.ROGALAND,
            EmailPayload(templateId = "team/varsel", to = ADDRESS, subject = "Emne", body = "Innhold"),
        )

    private companion object {
        const val RECIPIENT_PER_HOUR = 10
        const val ADDRESS = "  Personvern.Testmottaker@Rogfk.no "
    }
}
