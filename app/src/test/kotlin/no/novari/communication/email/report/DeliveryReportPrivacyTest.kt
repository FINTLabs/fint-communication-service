package no.novari.communication.email.report

import com.azure.storage.queue.QueueClient
import io.micrometer.core.instrument.MeterRegistry
import no.novari.communication.message.MessageService
import no.novari.communication.message.dispatch.DispatchWorker
import no.novari.communication.message.domain.EmailPayload
import no.novari.communication.message.domain.MessageId
import no.novari.communication.model.Tenant
import no.novari.communication.recipient.RecipientHasher
import org.assertj.core.api.Assertions.assertThat
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
import org.testcontainers.azure.AzuriteContainer

@DeliveryReportTest
@ExtendWith(OutputCaptureExtension::class)
class DeliveryReportPrivacyTest {
    @Autowired
    lateinit var poller: DeliveryReportPoller

    @Autowired
    lateinit var queueMetrics: DeliveryReportQueueMetrics

    @Autowired
    lateinit var messageService: MessageService

    @Autowired
    lateinit var dispatchWorker: DispatchWorker

    @Autowired
    lateinit var hasher: RecipientHasher

    @Autowired
    lateinit var jdbcClient: JdbcClient

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var azurite: AzuriteContainer

    private lateinit var queueClient: QueueClient
    private lateinit var messages: List<MessageId>

    @BeforeEach
    fun receiveReportsWithTheAddress() {
        queueClient = StorageDeliveryReportQueue.clientBuilder(azurite.connectionString, QUEUE_NAME).buildClient()
        queueClient.createIfNotExists()
        queueClient.clearMessages()
        jdbcClient.sql("DELETE FROM send_usage").update()
        jdbcClient.sql("DELETE FROM recipient_blocklist").update()
        jdbcClient.sql("DELETE FROM dispatch_queue").update()
        jdbcClient.sql("DELETE FROM message").update()

        messages = listOf("Bounced", "Failed", "Delivered").map { send() }
        dispatchWorker.processDue()
        listOf("Bounced", "Failed", "Delivered").zip(messages).forEach { (status, id) -> enqueue(event(id, status)) }
        enqueue(event(MessageId.generate(), "Suppressed"))
        enqueue("{\"data\": {\"recipient\": \"$ADDRESS\"")
        enqueue(event(MessageId.generate(), "Bounced").replace("\"specversion\"", "\"ikke-gyldig\": $ADDRESS, \"x\""))

        poller.poll()
        queueMetrics.refresh()
    }

    @Test
    fun `the reports are stored as status and a hashed blocking`() {
        assertThat(
            jdbcClient
                .sql("SELECT count(*) FROM recipient_blocklist WHERE recipient_hash = :hash AND source = 'ACS'")
                .param("hash", hasher.hash(ADDRESS).value)
                .query(Long::class.java)
                .single(),
        ).isEqualTo(1L)
        assertThat(queueClient.properties.approximateMessagesCountLong).isZero()
    }

    @Test
    fun `neither the address nor the hash ends up in logs`(output: CapturedOutput) {
        assertThat(output.all)
            .contains("Leveringsrapport id=${messages.first().value}")
            .doesNotContainIgnoringCase(ADDRESS.trim())
            .doesNotContainIgnoringCase(STATUS_MESSAGE)
            .doesNotContain(hasher.hash(ADDRESS).value)
    }

    @Test
    fun `no text column in the database contains the address`() {
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
    fun `no metric tag contains the address or the hash`() {
        val tagValues = meterRegistry.meters.flatMap { meter -> meter.id.tags.map { it.value } }

        assertThat(tagValues)
            .noneMatch { it.contains(ADDRESS.trim(), ignoreCase = true) }
            .doesNotContain(hasher.hash(ADDRESS).value)
    }

    @Test
    fun `the prometheus scrape has the report metrics but neither address nor hash`() {
        val scrape =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .build()
                .get("/actuator/prometheus")
                .andExpect { status { isOk() } }
                .andReturn()
                .response.contentAsString

        assertThat(scrape)
            .containsPattern(
                """communication_delivery_report_received_total\{channel="EMAIL",status="BOUNCED",""" +
                    """template="team/varsel",tenant="ROGALAND"}""",
            ).containsPattern("""communication_delivery_report_discarded_total\{reason="invalid"}""")
            .containsPattern("""communication_delivery_report_discarded_total\{reason="unknown-message"}""")
            .contains("communication_delivery_report_queue_size ")
            .doesNotContainIgnoringCase(ADDRESS.trim())
            .doesNotContain(hasher.hash(ADDRESS).value)
    }

    @Test
    fun `the status of a message has neither address nor hash`() {
        val mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()

        listOf("BOUNCED", "FAILED", "DELIVERED").zip(messages).forEach { (status, id) ->
            val response =
                mockMvc
                    .get("/api/v1/messages/${id.value}")
                    .andExpect {
                        status { isOk() }
                        jsonPath("$.status") { value(status) }
                    }.andReturn()
                    .response.contentAsString

            assertThat(response)
                .doesNotContainIgnoringCase(ADDRESS.trim())
                .doesNotContainIgnoringCase(STATUS_MESSAGE)
                .doesNotContain(hasher.hash(ADDRESS).value)
        }
    }

    private fun send(): MessageId =
        messageService.receive(
            Tenant.ROGALAND,
            EmailPayload(templateId = "team/varsel", to = ADDRESS, subject = "Emne", body = "Innhold"),
        )

    private fun event(
        id: MessageId,
        status: String,
    ): String = DeliveryReportEvents.cloudEvent(id, status, recipient = ADDRESS, statusMessage = STATUS_MESSAGE)

    private fun enqueue(event: String) {
        queueClient.sendMessage(DeliveryReportEvents.base64(event))
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

    private companion object {
        const val QUEUE_NAME = "email-delivery-reports"
        const val ADDRESS = "Personvern.Rapport@Rogfk.no"
        const val STATUS_MESSAGE = "550 5.1.1 Personvern.Rapport@Rogfk.no: mailbox unavailable"
    }
}
