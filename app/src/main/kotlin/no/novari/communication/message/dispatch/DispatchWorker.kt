package no.novari.communication.message.dispatch

import io.github.oshai.kotlinlogging.KotlinLogging
import no.novari.communication.email.EmailAdapter
import no.novari.communication.email.EmailSendOutcome
import no.novari.communication.email.FailureReason
import no.novari.communication.email.RetryReason
import no.novari.communication.message.domain.MessageChannel
import no.novari.communication.message.domain.MessageStatus
import org.springframework.dao.DataAccessException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

@Component
class DispatchWorker(
    private val repository: DispatchQueueRepository,
    private val codec: PayloadCodec,
    private val emailAdapter: EmailAdapter,
    private val metrics: DispatchMetrics,
    private val properties: DispatchProperties,
    private val clock: Clock,
) {
    private val log = KotlinLogging.logger {}

    @Scheduled(fixedDelayString = "\${communication.dispatch.poll-interval}")
    fun processDue() {
        val now = clock.instant()
        val claimed =
            try {
                repository.claimDue(now, now + properties.lease, properties.batchSize)
            } catch (exception: DataAccessException) {
                log.warn { "Kunne ikke hente meldinger fra køen: ${exception.javaClass.simpleName}" }
                return
            }
        claimed.forEach(::process)
    }

    private fun process(message: QueuedMessage) {
        val outcome = send(message)
        try {
            when (outcome) {
                EmailSendOutcome.Sent -> sent(message)
                is EmailSendOutcome.Retryable -> retryOrFail(message, outcome)
                is EmailSendOutcome.Permanent -> failed(message, outcome.reason, outcome.detail)
            }
        } catch (exception: DataAccessException) {
            log.warn {
                "Kunne ikke lagre resultatet av utsendingen id=${message.id.value}; meldingen tas på nytt når " +
                    "leasen går ut: ${exception.javaClass.simpleName}"
            }
        }
    }

    private fun send(message: QueuedMessage): EmailSendOutcome =
        try {
            when (message.channel) {
                MessageChannel.EMAIL -> {
                    emailAdapter.send(message.id, codec.decodeEmail(message.id, message.templateId, message.payload))
                }
            }
        } catch (exception: Exception) {
            EmailSendOutcome.Retryable(RetryReason.UNEXPECTED, exception.javaClass.name)
        }

    private fun sent(message: QueuedMessage) {
        if (!repository.delete(message)) return logLeaseLost(message)
        metrics.sent(message)
        log.info { "Melding sendt ${describe(message, MessageStatus.SENT)}" }
    }

    private fun retryOrFail(
        message: QueuedMessage,
        outcome: EmailSendOutcome.Retryable,
    ) {
        if (message.attempts >= properties.maxAttempts) {
            return failed(message, FailureReason.RETRIES_EXHAUSTED, "${outcome.reason.value} ${outcome.detail}")
        }
        val delay = maxOf(properties.backoffAfter(message.attempts), outcome.retryAfter ?: Duration.ZERO)
        if (!repository.reschedule(message, clock.instant() + delay)) return logLeaseLost(message)
        metrics.retried(message, outcome.reason)
        log.warn {
            "Utsending feilet, prøver igjen om $delay ${describe(
                message,
                MessageStatus.RECEIVED,
            )} årsak=${outcome.reason.value} " +
                "detalj=${outcome.detail}"
        }
    }

    private fun failed(
        message: QueuedMessage,
        reason: FailureReason,
        detail: String,
    ) {
        if (!repository.delete(message)) return logLeaseLost(message)
        metrics.failed(message, reason)
        log.warn { "Melding feilet ${describe(message, MessageStatus.FAILED)} årsak=${reason.value} detalj=$detail" }
    }

    private fun logLeaseLost(message: QueuedMessage) {
        log.warn {
            "Meldingen er tatt over av et nytt forsøk etter at leasen gikk ut ${describe(
                message,
                MessageStatus.PROCESSING,
            )}"
        }
    }

    private fun describe(
        message: QueuedMessage,
        status: MessageStatus,
    ): String =
        "id=${message.id.value} status=$status tenant=${message.tenant} channel=${message.channel} " +
            "templateId=${message.templateId} forsøk=${message.attempts}"
}
