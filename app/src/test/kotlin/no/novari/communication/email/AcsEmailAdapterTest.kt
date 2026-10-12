package no.novari.communication.email

import com.azure.core.http.HttpMethod
import no.novari.communication.message.domain.EmailPayload
import no.novari.communication.message.domain.MessageId
import no.novari.communication.model.FailureReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.time.Duration

class AcsEmailAdapterTest {
    private val acs = FakeAcs()
    private val id = MessageId.generate()
    private val email =
        EmailPayload(
            templateId = "team/varsel",
            to = "ola.nordmann@rogfk.no",
            subject = "Sak 2026-1",
            body = "<p>Innhold</p>",
            replyTo = "no-reply@novari.no",
        )

    private fun adapter(sdkRetries: Int = 0) = AcsEmailAdapter(acs.client(sdkRetries), SENDER)

    @Test
    fun `maps the email to the ACS send request`() {
        assertThat(adapter().send(id, email)).isEqualTo(EmailSendOutcome.Sent)

        val request = JsonMapper.builder().build().readTree(acs.sendRequests.single().body)
        assertThat(request.path("senderAddress").asString()).isEqualTo(SENDER)
        assertThat(
            request
                .path("recipients")
                .path("to")
                .toList()
                .map { it.path("address").asString() },
        ).containsExactly("ola.nordmann@rogfk.no")
        assertThat(request.path("content").path("subject").asString()).isEqualTo("Sak 2026-1")
        assertThat(request.path("content").path("html").asString()).isEqualTo("<p>Innhold</p>")
        assertThat(request.path("content").has("plainText")).isFalse()
        assertThat(
            request.path("replyTo").toList().map { it.path("address").asString() },
        ).containsExactly("no-reply@novari.no")
        assertThat(request.path("userEngagementTrackingDisabled").asBoolean()).isTrue()
        assertThat(request.has("attachments")).isFalse()
    }

    @Test
    fun `leaves out reply-to when the template has none`() {
        adapter().send(id, email.copy(replyTo = null))

        val request = JsonMapper.builder().build().readTree(acs.sendRequests.single().body)
        assertThat(request.has("replyTo")).isFalse()
    }

    @Test
    fun `sends the message id as operation id`() {
        adapter().send(id, email)

        assertThat(
            acs.sendRequests
                .single()
                .headers
                .getValue(OperationIdPolicy.OPERATION_ID_HEADER),
        ).isEqualTo(id.value.toString())
    }

    @Test
    fun `the sdk retries with the same operation id`() {
        acs.onSend(FakeAcs.error(503, "ServiceUnavailable"), FakeAcs.accepted())

        assertThat(adapter(sdkRetries = 1).send(id, email)).isEqualTo(EmailSendOutcome.Sent)

        assertThat(acs.sendRequests.map { it.headers.getValue(OperationIdPolicy.OPERATION_ID_HEADER) })
            .containsExactly(id.value.toString(), id.value.toString())
    }

    @Test
    fun `an accepted send is sent without asking ACS for the operation status`() {
        assertThat(adapter().send(id, email)).isEqualTo(EmailSendOutcome.Sent)

        assertThat(acs.requests).singleElement().extracting { it.method }.isEqualTo(HttpMethod.POST)
    }

    @Test
    fun `the sdk does not retry throttling`() {
        acs.onSend(
            FakeAcs.error(429, "TooManyRequests", headers = mapOf("Retry-After" to "120")),
            FakeAcs.accepted(),
        )

        val outcome = adapter(sdkRetries = 3).send(id, email)

        assertThat(outcome).isInstanceOfSatisfying(EmailSendOutcome.Retryable::class.java) {
            assertThat(it.reason).isEqualTo(RetryReason.THROTTLED)
        }
        assertThat(acs.sendRequests).hasSize(1)
    }

    @Test
    fun `a bad request is permanent with the status and error code but not the message`() {
        acs.onSend(FakeAcs.error(400, "InvalidRecipient", "ola.nordmann@rogfk.no er ugyldig"))

        val outcome = adapter().send(id, email)

        assertThat(outcome).isInstanceOfSatisfying(EmailSendOutcome.Permanent::class.java) {
            assertThat(it.reason).isEqualTo(FailureReason.REJECTED)
            assertThat(it.detail).isEqualTo("HTTP 400 error=InvalidRecipient")
        }
    }

    @Test
    fun `throttling is retryable after the time ACS asks for`() {
        acs.onSend(FakeAcs.error(429, "TooManyRequests", headers = mapOf("Retry-After" to "120")))

        assertThat(adapter().send(id, email))
            .isEqualTo(
                EmailSendOutcome.Retryable(
                    RetryReason.THROTTLED,
                    "HTTP 429 error=TooManyRequests",
                    Duration.ofSeconds(120),
                ),
            )
    }

    @Test
    fun `server errors, timeouts and authentication errors are retryable`() {
        val reasons =
            mapOf(500 to RetryReason.SERVER_ERROR, 503 to RetryReason.SERVER_ERROR, 408 to RetryReason.TIMEOUT)
                .plus(401 to RetryReason.UNAUTHORIZED)
                .plus(403 to RetryReason.UNAUTHORIZED)

        reasons.forEach { (status, reason) ->
            acs.onSend(FakeAcs.error(status, "Feil"))

            assertThat(adapter().send(id, email))
                .`as`("HTTP $status")
                .isInstanceOfSatisfying(EmailSendOutcome.Retryable::class.java) {
                    assertThat(it.reason).isEqualTo(reason)
                }
        }
    }

    @Test
    fun `a network error is retryable`() {
        acs.onSend(FakeAcs.Response.Failure(IOException("Connection reset")))

        assertThat(adapter().send(id, email)).isInstanceOfSatisfying(EmailSendOutcome.Retryable::class.java) {
            assertThat(it.reason).isEqualTo(RetryReason.IO)
        }
    }

    private companion object {
        const val SENDER = "no-reply@novari.no"
    }
}
