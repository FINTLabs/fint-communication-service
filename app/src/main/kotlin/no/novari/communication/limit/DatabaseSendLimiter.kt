package no.novari.communication.limit

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.novari.communication.message.domain.OutgoingMessage
import no.novari.communication.recipient.RecipientHash
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Component
class DatabaseSendLimiter(
    private val repository: SendUsageRepository,
    private val properties: LimitProperties,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) : SendLimiter {
    private val recipientLimits = properties.recipient.slidingWindows()
    private val totalLimits = properties.total.slidingWindows()

    // Låsene slippes først ved commit, så kallerens transaksjon må også dekke dispatch.
    // Den globale låsen serialiserer alle innsendinger og dekker dermed også tenantgrensen.
    @Transactional(propagation = Propagation.MANDATORY)
    override fun checkAndRecord(
        message: OutgoingMessage,
        recipient: RecipientHash,
    ) {
        repository.setLockTimeout(LOCK_TIMEOUT)
        repository.lockRecipient(recipient)
        repository.lockTotal()
        // Leses etter låsene: receivedAt kan være flere sekunder gammelt hvis forespørselen ventet på en lås.
        val now = clock.instant()
        val since = now - DAY
        val exceeded =
            listOf(
                LimitType.RECIPIENT to
                    recipientLimits.retryAfter(repository.recipientSentTimesSince(recipient, since), now),
                LimitType.TENANT to
                    properties.tenant
                        .forTenant(message.tenant)
                        .slidingWindows()
                        .retryAfter(repository.tenantSentTimesSince(message.tenant, since), now),
                LimitType.TOTAL to totalLimits.retryAfter(repository.sentTimesSince(since), now),
            ).mapNotNull { (type, retryAfter) -> retryAfter?.let { type to it } }
                .maxByOrNull { (_, retryAfter) -> retryAfter }
        if (exceeded != null) {
            val (type, retryAfter) = exceeded
            Counter
                .builder(REJECTED_METRIC)
                .tag("limit", type.value)
                .tag("tenant", message.tenant.name)
                .register(meterRegistry)
                .increment()
            throw LimitExceededException(type, message.tenant, message.id, retryAfter)
        }
        repository.record(message.id, recipient, message.tenant, now)
    }

    private fun WindowLimits.slidingWindows(): List<SlidingWindowLimit> =
        listOf(SlidingWindowLimit(perHour, HOUR), SlidingWindowLimit(perDay, DAY))

    private fun List<SlidingWindowLimit>.retryAfter(
        sentTimes: List<Instant>,
        now: Instant,
    ): Duration? = mapNotNull { it.retryAfter(sentTimes, now) }.maxOrNull()

    companion object {
        const val REJECTED_METRIC = "communication.limit.rejected"
        val HOUR: Duration = Duration.ofHours(1)
        val DAY: Duration = Duration.ofDays(1)
        private val LOCK_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
