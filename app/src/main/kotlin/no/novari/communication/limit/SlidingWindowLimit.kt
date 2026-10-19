package no.novari.communication.limit

import java.time.Duration
import java.time.Instant

data class SlidingWindowLimit(
    val limit: Int,
    val window: Duration,
) {
    fun retryAfter(
        sentTimesAscending: List<Instant>,
        now: Instant,
    ): Duration? {
        val windowStart = now - window
        val inWindow = sentTimesAscending.filter { it > windowStart }
        if (inWindow.size < limit) return null
        return Duration.between(now, inWindow[inWindow.size - limit] + window)
    }
}
