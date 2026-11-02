package no.novari.communication.email

import no.novari.communication.message.domain.EmailPayload
import no.novari.communication.message.domain.MessageId
import no.novari.communication.model.FailureReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.io.IOException

class SesEmailAdapterTest {
    private val ses = FakeSes()
    private val id = MessageId.generate()
    private val email =
        EmailPayload(
            templateId = "team/varsel",
            to = "ola.nordmann@rogfk.no",
            subject = "Sak 2026-1",
            body = "<p>Innhold</p>",
            replyTo = "no-reply@novari.no",
        )

    private fun adapter(
        sdkRetries: Int = 0,
        configurationSet: String? = CONFIGURATION_SET,
    ) = SesEmailAdapter(ses.client(sdkRetries), SENDER, configurationSet)

    private fun lastRequest() = JsonMapper.builder().build().readTree(ses.requests.last().body)

    @Test
    fun `maps the email to the SES send request`() {
        assertThat(adapter().send(id, email)).isEqualTo(EmailSendOutcome.Sent)

        assertThat(ses.requests.single().path).isEqualTo("/v2/email/outbound-emails")
        val request = lastRequest()
        assertThat(request.path("FromEmailAddress").asString()).isEqualTo(SENDER)
        assertThat(
            request
                .path("Destination")
                .path("ToAddresses")
                .toList()
                .map { it.asString() },
        ).containsExactly("ola.nordmann@rogfk.no")
        assertThat(request.path("Destination").has("CcAddresses")).isFalse()
        assertThat(request.path("Destination").has("BccAddresses")).isFalse()
        assertThat(request.path("ReplyToAddresses").toList().map { it.asString() })
            .containsExactly("no-reply@novari.no")
        val message = request.path("Content").path("Simple")
        assertThat(message.path("Subject").path("Data").asString()).isEqualTo("Sak 2026-1")
        assertThat(message.path("Subject").path("Charset").asString()).isEqualTo("UTF-8")
        assertThat(
            message
                .path("Body")
                .path("Html")
                .path("Data")
                .asString(),
        ).isEqualTo("<p>Innhold</p>")
        assertThat(message.path("Body").has("Text")).isFalse()
        assertThat(message.has("Attachments")).isFalse()
        assertThat(request.path("ConfigurationSetName").asString()).isEqualTo(CONFIGURATION_SET)
    }

    @Test
    fun `tags the email with the message id`() {
        adapter().send(id, email)

        val tags = lastRequest().path("EmailTags").toList()
        assertThat(tags).singleElement().satisfies({
            assertThat(it.path("Name").asString()).isEqualTo(SesEmailAdapter.MESSAGE_ID_TAG)
            assertThat(it.path("Value").asString()).isEqualTo(id.value.toString())
        })
    }

    @Test
    fun `leaves out reply-to and configuration set when they are not set`() {
        adapter(configurationSet = null).send(id, email.copy(replyTo = null))

        val request = lastRequest()
        assertThat(request.has("ReplyToAddresses")).isFalse()
        assertThat(request.has("ConfigurationSetName")).isFalse()
    }

    @Test
    fun `a rejected message is permanent with the status and error code but not the message`() {
        ses.onSend(FakeSes.error(400, "MessageRejected", "Email address is not verified: ola.nordmann@rogfk.no"))

        val outcome = adapter().send(id, email)

        assertThat(outcome).isInstanceOfSatisfying(EmailSendOutcome.Permanent::class.java) {
            assertThat(it.reason).isEqualTo(FailureReason.REJECTED)
            assertThat(it.detail).isEqualTo("HTTP 400 error=MessageRejected")
        }
    }

    @Test
    fun `throttling is retryable`() {
        ses.onSend(FakeSes.error(429, "TooManyRequestsException"))

        assertThat(adapter().send(id, email))
            .isEqualTo(EmailSendOutcome.Retryable(RetryReason.THROTTLED, "HTTP 429 error=TooManyRequestsException"))
    }

    @Test
    fun `a paused or suspended account is retryable until it is fixed`() {
        listOf("SendingPausedException", "AccountSuspendedException").forEach { errorType ->
            ses.onSend(FakeSes.error(400, errorType))

            assertThat(adapter().send(id, email))
                .`as`(errorType)
                .isInstanceOfSatisfying(EmailSendOutcome.Retryable::class.java) {
                    assertThat(it.reason).isEqualTo(RetryReason.UNAUTHORIZED)
                }
        }
    }

    @Test
    fun `server errors, timeouts and authentication errors are retryable`() {
        val reasons =
            mapOf(
                500 to RetryReason.SERVER_ERROR,
                503 to RetryReason.SERVER_ERROR,
                408 to RetryReason.TIMEOUT,
                401 to RetryReason.UNAUTHORIZED,
                403 to RetryReason.UNAUTHORIZED,
            )

        reasons.forEach { (status, reason) ->
            ses.onSend(FakeSes.error(status, "Feil"))

            assertThat(adapter().send(id, email))
                .`as`("HTTP $status")
                .isInstanceOfSatisfying(EmailSendOutcome.Retryable::class.java) {
                    assertThat(it.reason).isEqualTo(reason)
                }
        }
    }

    @Test
    fun `a network error is retryable`() {
        ses.onSend(FakeSes.Response.Failure(IOException("Connection reset")))

        assertThat(adapter().send(id, email)).isInstanceOfSatisfying(EmailSendOutcome.Retryable::class.java) {
            assertThat(it.reason).isEqualTo(RetryReason.IO)
        }
    }

    @Test
    fun `the sdk retries server errors before the adapter gets the answer`() {
        ses.onSend(FakeSes.error(503, "ServiceUnavailable"), FakeSes.accepted())

        assertThat(adapter(sdkRetries = 1).send(id, email)).isEqualTo(EmailSendOutcome.Sent)
        assertThat(ses.requests).hasSize(2)
    }

    private companion object {
        const val SENDER = "no-reply@novari.no"
        const val CONFIGURATION_SET = "fint-communication"
    }
}
