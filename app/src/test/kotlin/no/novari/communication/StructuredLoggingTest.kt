package no.novari.communication

import no.novari.communication.message.MessageService
import no.novari.communication.message.domain.EmailPayload
import no.novari.communication.model.Tenant
import no.novari.communication.recipient.RecipientHasher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.json.JsonMapper

@IntegrationTest
@ExtendWith(OutputCaptureExtension::class)
class StructuredLoggingTest {
    @Autowired
    lateinit var messageService: MessageService

    @Autowired
    lateinit var hasher: RecipientHasher

    @Autowired
    lateinit var jdbcClient: JdbcClient

    @Autowired
    lateinit var jsonMapper: JsonMapper

    @Test
    fun `log lines are logstash json without the recipient`(output: CapturedOutput) {
        jdbcClient.sql("DELETE FROM send_usage").update()

        val id =
            messageService.receive(
                Tenant.ROGALAND,
                EmailPayload(templateId = "team/varsel", to = ADDRESS, subject = "Emne", body = "Innhold"),
            )

        val line =
            output.out
                .lines()
                .filter { it.startsWith("{") }
                .map { jsonMapper.readTree(it) }
                .single { it.path("message").asString().contains(id.value.toString()) }

        assertThat(line.path("@timestamp").asString()).isNotBlank()
        assertThat(line.path("level").asString()).isEqualTo("INFO")
        assertThat(line.path("logger_name").asString()).endsWith("QueueingMessageDispatcher")
        assertThat(line.path("message").asString()).contains("tenant=ROGALAND")
        assertThat(output.all)
            .doesNotContainIgnoringCase(ADDRESS)
            .doesNotContain(hasher.hash(ADDRESS).value)
    }

    private companion object {
        const val ADDRESS = "strukturert.logging@rogfk.no"
    }
}
