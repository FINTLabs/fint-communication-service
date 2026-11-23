package no.novari.communication.limit

import no.novari.communication.message.domain.OutgoingMessage
import no.novari.communication.recipient.RecipientHash
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class DatabaseSendLimiter(
    private val repository: SendUsageRepository,
    properties: LimitProperties,
) : SendLimiter {
    private val recipientLimits =
        listOf(
            SlidingWindowLimit(properties.recipient.perHour, HOUR),
            SlidingWindowLimit(properties.recipient.perDay, DAY),
        )

    // shortcut: parallel requests to the same recipient can each pass the check and overshoot the limit by a few
    // messages; take pg_advisory_xact_lock before counting if the limit must be exact.
    override fun checkAndRecord(
        message: OutgoingMessage,
        recipient: RecipientHash,
    ) {
        val now = message.receivedAt
        val sentTimes = repository.recipientSentTimesSince(recipient, now - DAY)
        val retryAfter = recipientLimits.mapNotNull { it.retryAfter(sentTimes, now) }.maxOrNull()
        if (retryAfter != null) {
            throw LimitExceededException(LimitType.RECIPIENT, message.tenant, message.id, retryAfter)
        }
        repository.record(message.id, recipient, message.tenant, now)
    }

    companion object {
        val HOUR: Duration = Duration.ofHours(1)
        val DAY: Duration = Duration.ofDays(1)
    }
}
