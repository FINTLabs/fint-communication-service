package no.novari.communication.api

import no.novari.communication.TEST_RECIPIENT_HASHING_KEY
import no.novari.communication.api.validation.SendMessageRequestValidator
import no.novari.communication.blocklist.RecipientBlockedException
import no.novari.communication.blocklist.RecipientBlocklist
import no.novari.communication.limit.LimitExceededException
import no.novari.communication.limit.LimitType
import no.novari.communication.limit.SendLimiter
import no.novari.communication.message.MessageService
import no.novari.communication.message.MessageStore
import no.novari.communication.message.StoredMessage
import no.novari.communication.message.dispatch.MessageDispatcher
import no.novari.communication.message.domain.EmailPayload
import no.novari.communication.message.domain.MessageChannel
import no.novari.communication.message.domain.MessageId
import no.novari.communication.message.domain.OutgoingMessage
import no.novari.communication.model.FailureReason
import no.novari.communication.model.MessageStatus
import no.novari.communication.model.Tenant
import no.novari.communication.recipient.RecipientHash
import no.novari.communication.recipient.RecipientHasher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skyscreamer.jsonassert.JSONAssert
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

@WebMvcTest(MessageController::class)
@Import(SendMessageRequestValidator::class, MessageService::class, MessageControllerTest.TestBeans::class)
class MessageControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var dispatcher: RecordingDispatcher

    @Autowired
    lateinit var sendLimiter: ControllableSendLimiter

    @Autowired
    lateinit var blocklist: ControllableBlocklist

    @Autowired
    lateinit var messageStore: InMemoryMessageStore

    @BeforeEach
    fun reset() {
        messageStore.messages.clear()
        messageStore.failure = null
        dispatcher.dispatched.clear()
        dispatcher.failure = null
        sendLimiter.failure = null
        blocklist.blocked = false
    }

    @Test
    fun `a valid request is accepted with the id of the dispatched message`() {
        val response =
            postJson(VALID_REQUEST)
                .andExpect {
                    status { isAccepted() }
                    content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
                    jsonPath("$.id") { isString() }
                }.andReturn()
                .response.contentAsString

        val message = dispatcher.dispatched.single()
        assertThat(response).isEqualTo("""{"id":"${message.id.value}"}""")
        assertThat(messageStore.messages.keys).containsExactly(message.id)
        assertThat(message.tenant).isEqualTo(Tenant.ROGALAND)
        assertThat(message.receivedAt).isEqualTo(NOW)
        assertThat(message.payload).isEqualTo(
            EmailPayload(
                templateId = TestTemplates.ID,
                to = "ola@rogfk.no",
                subject = "Sak 2026-1",
                body = "<p>2026-1</p><ul><li>ACOS</li><li>eApply</li></ul>",
                replyTo = "no-reply@novari.no",
            ),
        )
    }

    @Test
    fun `an invalid request is rejected with problem details listing every invalid field`() {
        postJson(
            """
            {
              "tenant": "ROGALAND",
              "message": {
                "channel": "EMAIL",
                "to": "ikke-en-adresse",
                "templateId": "team/varsel",
                "variables": { "saksnummer": "hemmelig-og-for-lang" },
                "lists": { "rader": [ { "navn": "ACOS" } ] }
              }
            }
            """,
        ).andExpect {
            status { isBadRequest() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.status") { value(400) }
            jsonPath("$.title") { value("Bad Request") }
            jsonPath("$.detail") { value("Requesten inneholder ugyldige felt") }
            jsonPath("$.instance") { value(MessageController.MESSAGES_PATH) }
            jsonPath("$.errors[0].field") { value("message.to") }
            jsonPath("$.errors[0].message") { value("må være én gyldig e-postadresse") }
            jsonPath("$.errors[1].field") { value("message.variables.saksnummer") }
            jsonPath("$.errors[1].message") { value("kan ikke være lengre enn 10 tegn") }
            content { string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hemmelig"))) }
        }
        assertThat(dispatcher.dispatched).isEmpty()
    }

    @Test
    fun `a missing required field is reported by its path`() {
        postJson(
            """{ "message": { "channel": "EMAIL", "to": "ola@rogfk.no", "templateId": "team/varsel" } }""",
        ).andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].field") { value("tenant") }
            jsonPath("$.errors[0].message") { value("mangler eller har feil type") }
        }
    }

    @Test
    fun `an unknown tenant is rejected with the valid tenants`() {
        postJson(VALID_REQUEST.replace("ROGALAND", "rogfk.no")).andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].field") { value("tenant") }
            jsonPath("$.errors[0].message") { value("må være en av: ${Tenant.entries.joinToString()}") }
        }
    }

    @Test
    fun `a message without channel is rejected with the valid channels`() {
        postJson(VALID_REQUEST.replace("\"channel\": \"EMAIL\",", "")).andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].field") { value("message.channel") }
            jsonPath("$.errors[0].message") { value("må være en av: EMAIL") }
        }
    }

    @Test
    fun `an unknown channel is rejected with the valid channels`() {
        postJson(VALID_REQUEST.replace("\"channel\": \"EMAIL\"", "\"channel\": \"SMS\"")).andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].field") { value("message.channel") }
            jsonPath("$.errors[0].message") { value("må være en av: EMAIL") }
        }
    }

    @Test
    fun `a value of the wrong type is reported by its path without echoing the value`() {
        postJson(
            """
            { "tenant": "ROGALAND",
              "message": { "channel": "EMAIL", "to": "ola@rogfk.no", "templateId": "team/varsel", "variables": { "saksnummer": ["hemmelig"] } } }
            """,
        ).andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].field") { value("message.variables.saksnummer") }
            content { string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hemmelig"))) }
        }
    }

    @Test
    fun `malformed json is rejected`() {
        postJson("""{ "tenant": "ROGALAND", """).andExpect {
            status { isBadRequest() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.detail") { value("Request-body er ikke gyldig JSON") }
        }
    }

    @Test
    fun `a body that is not json is rejected as unsupported media type`() {
        mockMvc
            .post(MessageController.MESSAGES_PATH) {
                contentType = MediaType.TEXT_PLAIN
                content = "hei"
            }.andExpect {
                status { isUnsupportedMediaType() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            }
    }

    @Test
    fun `other methods are not allowed`() {
        mockMvc.get(MessageController.MESSAGES_PATH).andExpect {
            status { isMethodNotAllowed() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
        }
    }

    @Test
    fun `an unexpected error returns a generic problem without internal details`() {
        dispatcher.failure = IllegalStateException("intern detalj ola@rogfk.no")

        postJson(VALID_REQUEST).andExpect {
            status { isInternalServerError() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.detail") { value("Det oppstod en uventet feil") }
            content { string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("intern detalj"))) }
        }
    }

    @Test
    fun `a message over the limit is rejected with retry-after and without the address`() {
        sendLimiter.failure = { message ->
            LimitExceededException(LimitType.RECIPIENT, message.tenant, message.id, Duration.ofMillis(2_500_001))
        }

        postJson(VALID_REQUEST).andExpect {
            status { isTooManyRequests() }
            header { string(HttpHeaders.RETRY_AFTER, "2501") }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.status") { value(429) }
            jsonPath("$.detail") { value("Mottakeren har fått for mange meldinger. Prøv igjen senere.") }
            jsonPath("$.limit") { value("mottaker") }
            jsonPath("$.instance") { value(MessageController.MESSAGES_PATH) }
            content { string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ola@rogfk.no"))) }
        }
        assertThat(dispatcher.dispatched).isEmpty()
    }

    @Test
    fun `a blocked recipient is rejected without reason or address`() {
        blocklist.blocked = true

        postJson(VALID_REQUEST).andExpect {
            status { isUnprocessableContent() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.title") { value("Unprocessable Content") }
            jsonPath("$.status") { value(422) }
            jsonPath("$.detail") { value("Mottakeren kan ikke motta e-post fra tjenesten.") }
            jsonPath("$.instance") { value(MessageController.MESSAGES_PATH) }
            content { string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ola@rogfk.no"))) }
        }
        assertThat(dispatcher.dispatched).isEmpty()
    }

    @Test
    fun `retry-after is at least one second`() {
        sendLimiter.failure = { message ->
            LimitExceededException(LimitType.RECIPIENT, message.tenant, message.id, Duration.ofMillis(1))
        }

        postJson(VALID_REQUEST).andExpect {
            status { isTooManyRequests() }
            header { string(HttpHeaders.RETRY_AFTER, "1") }
        }
    }

    @Test
    fun `an unavailable database returns service unavailable`() {
        sendLimiter.failure = { CannotGetJdbcConnectionException("Failed to obtain JDBC Connection") }

        postJson(VALID_REQUEST).andExpect {
            status { isServiceUnavailable() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.detail") { value("Tjenesten er midlertidig utilgjengelig") }
        }
        assertThat(dispatcher.dispatched).isEmpty()
    }

    @Test
    fun `the status of a message has only id, status, failure reason and timestamps`() {
        val id = MessageId(UUID.randomUUID())
        messageStore.messages[id] = storedMessage(id, MessageStatus.SENT, null)

        val response =
            mockMvc
                .get("${MessageController.MESSAGES_PATH}/${id.value}")
                .andExpect {
                    status { isOk() }
                    content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
                }.andReturn()
                .response.contentAsString

        JSONAssert.assertEquals(
            """
            {
              "id": "${id.value}",
              "status": "SENT",
              "failureReason": null,
              "receivedAt": "2026-10-06T12:00:00Z",
              "updatedAt": "2026-10-06T12:00:03Z"
            }
            """,
            response,
            true,
        )
    }

    @Test
    fun `the status of a failed message has the failure reason`() {
        val id = MessageId(UUID.randomUUID())
        messageStore.messages[id] = storedMessage(id, MessageStatus.FAILED, FailureReason.REJECTED)

        mockMvc.get("${MessageController.MESSAGES_PATH}/${id.value}").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("FAILED") }
            jsonPath("$.failureReason") { value("REJECTED") }
        }
    }

    @Test
    fun `an unknown message id is not found`() {
        mockMvc.get("${MessageController.MESSAGES_PATH}/${UUID.randomUUID()}").andExpect {
            status { isNotFound() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.status") { value(404) }
            jsonPath("$.detail") { value("Meldingen finnes ikke") }
        }
    }

    @Test
    fun `a message id that is not a uuid is a bad request`() {
        mockMvc.get("${MessageController.MESSAGES_PATH}/ikke-en-uuid").andExpect {
            status { isBadRequest() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.errors[0].field") { value("id") }
            jsonPath("$.errors[0].message") { value("har feil format") }
        }
    }

    @Test
    fun `the status is unavailable when the database is`() {
        messageStore.failure = CannotGetJdbcConnectionException("Failed to obtain JDBC Connection")

        mockMvc.get("${MessageController.MESSAGES_PATH}/${UUID.randomUUID()}").andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.detail") { value("Tjenesten er midlertidig utilgjengelig") }
        }
    }

    private fun storedMessage(
        id: MessageId,
        status: MessageStatus,
        failureReason: FailureReason?,
    ) = StoredMessage(
        id = id,
        tenant = Tenant.ROGALAND,
        channel = MessageChannel.EMAIL,
        templateId = TestTemplates.ID,
        status = status,
        failureReason = failureReason,
        receivedAt = NOW,
        updatedAt = NOW + Duration.ofSeconds(3),
    )

    private fun postJson(json: String) =
        mockMvc.post(MessageController.MESSAGES_PATH) {
            contentType = MediaType.APPLICATION_JSON
            content = json
        }

    class RecordingDispatcher : MessageDispatcher {
        val dispatched = mutableListOf<OutgoingMessage>()
        var failure: RuntimeException? = null

        override fun dispatch(message: OutgoingMessage) {
            failure?.let { throw it }
            dispatched += message
        }
    }

    class ControllableSendLimiter : SendLimiter {
        var failure: ((OutgoingMessage) -> RuntimeException)? = null

        override fun checkAndRecord(
            message: OutgoingMessage,
            recipient: RecipientHash,
        ) {
            failure?.let { throw it(message) }
        }
    }

    class ControllableBlocklist : RecipientBlocklist {
        var blocked = false

        override fun checkNotBlocked(
            message: OutgoingMessage,
            recipient: RecipientHash,
        ) {
            if (blocked) throw RecipientBlockedException(message.tenant, message.id)
        }
    }

    class InMemoryMessageStore : MessageStore {
        val messages = mutableMapOf<MessageId, StoredMessage>()
        var failure: RuntimeException? = null

        override fun save(message: OutgoingMessage) {
            messages[message.id] =
                StoredMessage(
                    message.id,
                    message.tenant,
                    message.channel,
                    message.payload.templateId,
                    message.status,
                    null,
                    message.receivedAt,
                    message.receivedAt,
                )
        }

        override fun find(id: MessageId): StoredMessage? {
            failure?.let { throw it }
            return messages[id]
        }

        override fun complete(
            id: MessageId,
            status: MessageStatus,
            failureReason: FailureReason?,
            at: Instant,
        ) = throw UnsupportedOperationException()
    }

    @TestConfiguration
    class TestBeans {
        @Bean
        fun inMemoryMessageStore() = InMemoryMessageStore()

        @Bean
        fun emailTemplateCatalog() = TestTemplates.catalog()

        @Bean
        fun recordingDispatcher() = RecordingDispatcher()

        @Bean
        fun controllableSendLimiter() = ControllableSendLimiter()

        @Bean
        fun controllableBlocklist() = ControllableBlocklist()

        @Bean
        fun recipientHasher() = RecipientHasher(Base64.getDecoder().decode(TEST_RECIPIENT_HASHING_KEY))

        @Bean
        fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    companion object {
        private val NOW = Instant.parse("2026-10-06T12:00:00Z")
        private const val VALID_REQUEST =
            """
            {
              "tenant": "ROGALAND",
              "message": {
                "channel": "EMAIL",
                "to": "ola@rogfk.no",
                "templateId": "team/varsel",
                "variables": { "saksnummer": "2026-1" },
                "lists": { "rader": [ { "navn": "ACOS" }, { "navn": "eApply" } ] }
              }
            }
            """
    }
}
