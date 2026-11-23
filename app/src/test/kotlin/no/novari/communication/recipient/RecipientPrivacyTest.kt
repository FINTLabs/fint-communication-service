package no.novari.communication.recipient

import io.micrometer.core.instrument.MeterRegistry
import no.novari.communication.IntegrationTest
import no.novari.communication.TEST_RECIPIENT_HASHING_KEY
import no.novari.communication.limit.LimitExceededException
import no.novari.communication.limit.LimitUsageMetrics
import no.novari.communication.message.MessageService
import no.novari.communication.message.dispatch.DispatchQueueMetrics
import no.novari.communication.message.dispatch.DispatchWorker
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

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

    @Autowired
    lateinit var usageMetrics: LimitUsageMetrics

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var dispatchWorker: DispatchWorker

    @Autowired
    lateinit var queueMetrics: DispatchQueueMetrics

    @BeforeEach
    fun sendUntilTheLimitIsExceeded() {
        jdbcClient.sql("DELETE FROM send_usage").update()
        jdbcClient.sql("DELETE FROM dispatch_queue").update()
        repeat(RECIPIENT_PER_HOUR) { send() }
        assertThatThrownBy { send() }.isInstanceOf(LimitExceededException::class.java)

        usageMetrics.refresh()
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
        assertThat(textColumns()).contains(
            "send_usage" to "recipient_hash",
            "send_usage" to "tenant",
            "dispatch_queue" to "tenant",
            "dispatch_queue" to "template_id",
        )

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
    fun `no binary column in the database contains a plaintext address`() {
        assertThat(binaryColumns()).contains("dispatch_queue" to "payload")
        assertThat(jdbcClient.sql("SELECT count(*) FROM dispatch_queue").query(Long::class.java).single())
            .isEqualTo(RECIPIENT_PER_HOUR.toLong())

        val columnsContainingAddress =
            binaryColumns().filter { (table, column) ->
                jdbcClient
                    .sql(
                        """SELECT count(*) FROM "$table" WHERE position(convert_to(:address, 'UTF8') IN "$column") > 0""",
                    ).param("address", ADDRESS.trim())
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

    @Test
    fun `the prometheus scrape has the metrics per tenant but neither address nor hash`() {
        dispatchWorker.processDue()
        queueMetrics.refresh()

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
            .containsPattern("""communication_limit_tenant_usage_ratio\{tenant="ROGALAND",window="hour"}""")
            .containsPattern("""communication_limit_total_usage_ratio\{window="day"}""")
            .containsPattern("""communication_message_sent_total\{channel="EMAIL",tenant="ROGALAND"}""")
            .contains("communication_dispatch_queue_size ", "communication_dispatch_queue_oldest_seconds ")
        assertThat(scrape)
            .doesNotContainIgnoringCase(ADDRESS.trim())
            .doesNotContain(hasher.hash(ADDRESS).value)
    }

    private fun textColumns(): List<Pair<String, String>> =
        columns("'text', 'character varying', 'character', 'json', 'jsonb'")

    private fun binaryColumns(): List<Pair<String, String>> = columns("'bytea'")

    private fun columns(dataTypes: String): List<Pair<String, String>> =
        jdbcClient
            .sql(
                """
                SELECT table_name, column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND data_type IN ($dataTypes)
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
