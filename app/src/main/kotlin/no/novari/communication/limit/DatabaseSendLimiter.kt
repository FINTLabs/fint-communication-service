package no.novari.communication.limit

import no.novari.communication.message.domain.OutgoingMessage
import no.novari.communication.recipient.RecipientHash
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

@Component
class DatabaseSendLimiter(
    private val repository: SendUsageRepository,
    properties: LimitProperties,
    private val clock: Clock,
) : SendLimiter {
    private val recipientLimits =
        listOf(
            SlidingWindowLimit(properties.recipient.perHour, HOUR),
            SlidingWindowLimit(properties.recipient.perDay, DAY),
        )

    // The lock is released only on commit, so the caller's transaction must also cover dispatch.
    @Transactional(propagation = Propagation.MANDATORY)
    override fun checkAndRecord(
        message: OutgoingMessage,
        recipient: RecipientHash,
    ) {
        repository.setLockTimeout(LOCK_TIMEOUT)
        repository.lockRecipient(recipient)
        // Read after the lock: receivedAt can be several seconds old if the request waited for the lock.
        val now = clock.instant()
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
        private val LOCK_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
