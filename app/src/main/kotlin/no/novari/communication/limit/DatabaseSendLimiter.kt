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

    // The lock is released only on commit, so the caller's transaction must also cover dispatch.
    @Transactional(propagation = Propagation.MANDATORY)
    override fun checkAndRecord(
        message: OutgoingMessage,
        recipient: RecipientHash,
    ) {
        repository.setLockTimeout(LOCK_TIMEOUT)
        repository.lock()
        // Read after the lock: receivedAt can be several seconds old if the request waited for the lock.
        val now = clock.instant()
        val usage = repository.usageSince(now - DAY)
        val exceeded =
            listOf(
                LimitType.RECIPIENT to
                    recipientLimits.retryAfter(usage.filter { it.recipient == recipient }.map { it.sentAt }, now),
                LimitType.TENANT to
                    properties.tenant
                        .forTenant(message.tenant)
                        .slidingWindows()
                        .retryAfter(usage.filter { it.tenant == message.tenant }.map { it.sentAt }, now),
                LimitType.TOTAL to totalLimits.retryAfter(usage.map { it.sentAt }, now),
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
