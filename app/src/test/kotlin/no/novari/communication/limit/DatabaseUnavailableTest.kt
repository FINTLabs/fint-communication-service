package no.novari.communication.limit

import no.novari.communication.IntegrationTestConfiguration
import no.novari.communication.TEST_DISPATCH_ENCRYPTION_KEY
import no.novari.communication.TEST_RECIPIENT_HASHING_KEY
import no.novari.communication.api.MessageController
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.postgresql.PostgreSQLContainer

@SpringBootTest(
    properties = [
        "communication.recipient-hashing.key=$TEST_RECIPIENT_HASHING_KEY",
        "communication.dispatch.encryption-key=$TEST_DISPATCH_ENCRYPTION_KEY",
        "communication.dispatch.poll-interval=1h",
        "spring.datasource.hikari.connection-timeout=1000",
        "spring.datasource.hikari.validation-timeout=250",
    ],
)
@Import(DatabaseUnavailableTest.OwnDatabase::class, IntegrationTestConfiguration::class)
class DatabaseUnavailableTest {
    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var postgres: PostgreSQLContainer

    @Test
    fun `an unavailable database rejects the message with service unavailable`() {
        postgres.stop()

        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .build()
            .post(MessageController.MESSAGES_PATH) {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """
                    {
                      "tenant": "ROGALAND",
                      "message": {
                        "channel": "EMAIL",
                        "to": "ola@rogfk.no",
                        "templateId": "team/varsel",
                        "variables": { "saksnummer": "2026-1" },
                        "lists": { "rader": [ { "navn": "ACOS" } ] }
                      }
                    }
                    """.trimIndent()
            }.andExpect {
                status { isServiceUnavailable() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.detail") { value("Tjenesten er midlertidig utilgjengelig") }
                content { string(not(containsString("ola@rogfk.no"))) }
            }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class OwnDatabase {
        @Bean
        @ServiceConnection
        fun postgresContainer(): PostgreSQLContainer = PostgreSQLContainer("postgres:17-alpine")
    }
}
