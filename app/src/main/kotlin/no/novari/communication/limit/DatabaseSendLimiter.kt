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

    // Låsen slippes først ved commit, så kallerens transaksjon må også dekke dispatch.
    @Transactional(propagation = Propagation.MANDATORY)
    override fun checkAndRecord(
        message: OutgoingMessage,
        recipient: RecipientHash,
    ) {
        repository.setLockTimeout(LOCK_TIMEOUT)
        repository.lockRecipient(recipient)
        // Leses etter låsen: receivedAt kan være flere sekunder gammelt hvis forespørselen ventet på låsen.
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
