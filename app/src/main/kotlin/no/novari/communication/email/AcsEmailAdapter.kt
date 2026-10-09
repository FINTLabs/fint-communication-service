package no.novari.communication.email

import com.azure.communication.email.EmailClient
import com.azure.communication.email.EmailClientBuilder
import com.azure.communication.email.models.EmailAddress
import com.azure.communication.email.models.EmailMessage
import com.azure.communication.email.models.EmailSendResult
import com.azure.core.exception.HttpResponseException
import com.azure.core.http.HttpHeaderName
import com.azure.core.http.HttpResponse
import com.azure.core.http.policy.HttpLogDetailLevel
import com.azure.core.http.policy.HttpLogOptions
import com.azure.core.util.polling.LongRunningOperationStatus
import com.azure.core.util.polling.PollResponse
import no.novari.communication.message.domain.EmailPayload
import no.novari.communication.message.domain.MessageId
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeoutException

class AcsEmailAdapter(
    private val client: EmailClient,
    private val sender: String,
    private val operationTimeout: Duration,
) : EmailAdapter {
    override fun send(
        id: MessageId,
        email: EmailPayload,
    ): EmailSendOutcome =
        try {
            val poller = client.beginSend(email.toAcsMessage(), OperationIdPolicy.context(id))
            outcome(poller.waitForCompletion(operationTimeout))
        } catch (exception: HttpResponseException) {
            outcome(exception.response)
        } catch (exception: RuntimeException) {
            outcome(exception)
        }

    private fun EmailPayload.toAcsMessage(): EmailMessage {
        val message =
            EmailMessage()
                .setSenderAddress(sender)
                .setToRecipients(to)
                .setSubject(subject)
                .setBodyHtml(body)
                .setUserEngagementTrackingDisabled(true)
        return replyTo?.let { message.setReplyTo(EmailAddress(it)) } ?: message
    }

    private fun outcome(response: PollResponse<EmailSendResult>): EmailSendOutcome {
        val status = response.status
        return when {
            status == LongRunningOperationStatus.SUCCESSFULLY_COMPLETED -> {
                EmailSendOutcome.Sent
            }

            status.isComplete -> {
                EmailSendOutcome.Permanent(
                    FailureReason.OPERATION_FAILED,
                    "status=${response.value?.status ?: status} error=${response.value?.error?.code ?: "ukjent"}",
                )
            }

            else -> {
                EmailSendOutcome.Retryable(RetryReason.TIMEOUT, "status=${response.value?.status ?: status}")
            }
        }
    }

    private fun outcome(response: HttpResponse?): EmailSendOutcome {
        val statusCode =
            response?.statusCode ?: return EmailSendOutcome.Retryable(RetryReason.UNEXPECTED, "HTTP ukjent")
        val detail = "HTTP $statusCode error=${response.headers.getValue(ERROR_CODE_HEADER) ?: "ukjent"}"
        return when {
            statusCode == HTTP_TOO_MANY_REQUESTS -> {
                EmailSendOutcome.Retryable(RetryReason.THROTTLED, detail, retryAfter(response))
            }

            statusCode == HTTP_REQUEST_TIMEOUT -> {
                EmailSendOutcome.Retryable(RetryReason.TIMEOUT, detail)
            }

            statusCode == HTTP_UNAUTHORIZED || statusCode == HTTP_FORBIDDEN -> {
                EmailSendOutcome.Retryable(RetryReason.UNAUTHORIZED, detail)
            }

            statusCode >= HTTP_SERVER_ERROR -> {
                EmailSendOutcome.Retryable(RetryReason.SERVER_ERROR, detail)
            }

            else -> {
                EmailSendOutcome.Permanent(FailureReason.REJECTED, detail)
            }
        }
    }

    private fun outcome(exception: RuntimeException): EmailSendOutcome {
        val causes = generateSequence<Throwable>(exception) { it.cause }.toList()
        return when {
            causes.any { it is TimeoutException } -> {
                EmailSendOutcome.Retryable(RetryReason.TIMEOUT, "ingen sluttstatus innen $operationTimeout")
            }

            causes.any { it is IOException } -> {
                EmailSendOutcome.Retryable(RetryReason.IO, causes.first { it is IOException }.javaClass.simpleName)
            }

            else -> {
                EmailSendOutcome.Retryable(RetryReason.UNEXPECTED, exception.javaClass.simpleName)
            }
        }
    }

    private fun retryAfter(response: HttpResponse): Duration? =
        response.headers
            .getValue(HttpHeaderName.RETRY_AFTER)
            ?.toLongOrNull()
            ?.let(Duration::ofSeconds)

    companion object {
        private val ERROR_CODE_HEADER = HttpHeaderName.fromString("x-ms-error-code")
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR = 500

        fun clientBuilder(connectionString: String): EmailClientBuilder =
            EmailClientBuilder()
                .connectionString(connectionString)
                .addPolicy(OperationIdPolicy())
                .httpLogOptions(HttpLogOptions().setLogLevel(HttpLogDetailLevel.NONE))
    }
}
