package no.novari.communication.limit

import no.novari.communication.message.domain.OutgoingMessage
import no.novari.communication.recipient.RecipientHash
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
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

    // Låsen slippes først ved commit, så kallerens transaksjon må også dekke dispatch.
    @Transactional(propagation = Propagation.MANDATORY)
    override fun checkAndRecord(
        message: OutgoingMessage,
        recipient: RecipientHash,
    ) {
        repository.lockRecipient(recipient, LOCK_TIMEOUT)
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
        private val LOCK_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
