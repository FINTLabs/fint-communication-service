package no.novari.communication.email

import no.novari.communication.message.domain.EmailPayload
import no.novari.communication.message.domain.MessageId
import no.novari.communication.model.FailureReason
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException
import software.amazon.awssdk.core.exception.ApiCallTimeoutException
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sesv2.SesV2Client
import software.amazon.awssdk.services.sesv2.SesV2ClientBuilder
import software.amazon.awssdk.services.sesv2.model.AccountSuspendedException
import software.amazon.awssdk.services.sesv2.model.Body
import software.amazon.awssdk.services.sesv2.model.Content
import software.amazon.awssdk.services.sesv2.model.Destination
import software.amazon.awssdk.services.sesv2.model.EmailContent
import software.amazon.awssdk.services.sesv2.model.Message
import software.amazon.awssdk.services.sesv2.model.MessageTag
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest
import software.amazon.awssdk.services.sesv2.model.SendingPausedException
import software.amazon.awssdk.services.sesv2.model.SesV2Exception
import java.io.IOException
import java.time.Duration

class SesEmailAdapter(
    private val client: SesV2Client,
    private val sender: String,
    private val configurationSet: String?,
) : EmailAdapter {
    override fun send(
        id: MessageId,
        email: EmailPayload,
    ): EmailSendOutcome =
        try {
            client.sendEmail(email.toSendEmailRequest(id))
            EmailSendOutcome.Sent
        } catch (exception: SesV2Exception) {
            outcome(exception)
        } catch (exception: SdkClientException) {
            outcome(exception)
        }

    private fun EmailPayload.toSendEmailRequest(id: MessageId): SendEmailRequest =
        SendEmailRequest
            .builder()
            .fromEmailAddress(sender)
            .destination(Destination.builder().toAddresses(to).build())
            .apply { replyTo?.let { replyToAddresses(it) } }
            .content(
                EmailContent
                    .builder()
                    .simple(
                        Message
                            .builder()
                            .subject(utf8(subject))
                            .body(Body.builder().html(utf8(body)).build())
                            .build(),
                    ).build(),
            ).emailTags(
                MessageTag
                    .builder()
                    .name(MESSAGE_ID_TAG)
                    .value(id.value.toString())
                    .build(),
            ).configurationSetName(configurationSet)
            .build()

    private fun utf8(data: String): Content =
        Content
            .builder()
            .data(data)
            .charset("UTF-8")
            .build()

    private fun outcome(exception: SesV2Exception): EmailSendOutcome {
        val statusCode = exception.statusCode()
        val detail = "HTTP $statusCode error=${exception.awsErrorDetails()?.errorCode() ?: "ukjent"}"
        return when {
            exception is SendingPausedException || exception is AccountSuspendedException -> {
                EmailSendOutcome.Retryable(RetryReason.UNAUTHORIZED, detail)
            }

            exception.isThrottlingException || statusCode == HTTP_TOO_MANY_REQUESTS -> {
                EmailSendOutcome.Retryable(RetryReason.THROTTLED, detail)
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

    private fun outcome(exception: SdkClientException): EmailSendOutcome {
        if (exception is ApiCallTimeoutException || exception is ApiCallAttemptTimeoutException) {
            return EmailSendOutcome.Retryable(RetryReason.TIMEOUT, exception.javaClass.simpleName)
        }
        val io = generateSequence<Throwable>(exception) { it.cause }.firstOrNull { it is IOException }
        return if (io != null) {
            EmailSendOutcome.Retryable(RetryReason.IO, io.javaClass.simpleName)
        } else {
            EmailSendOutcome.Retryable(RetryReason.UNEXPECTED, exception.javaClass.simpleName)
        }
    }

    companion object {
        const val MESSAGE_ID_TAG = "messageId"
        private val API_CALL_TIMEOUT = Duration.ofSeconds(30)
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR = 500

        fun clientBuilder(region: String): SesV2ClientBuilder =
            SesV2Client
                .builder()
                .region(Region.of(region))
                .overrideConfiguration { it.apiCallTimeout(API_CALL_TIMEOUT) }
    }
}
