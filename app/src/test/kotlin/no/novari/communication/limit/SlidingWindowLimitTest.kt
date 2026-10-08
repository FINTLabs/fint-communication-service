package no.novari.communication.limit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class SlidingWindowLimitTest {
    private val start = Instant.parse("2026-10-08T12:00:00Z")
    private val limit = SlidingWindowLimit(limit = 3, window = Duration.ofHours(1))

    @Test
    fun `allows sending below the limit`() {
        val sent = listOf(start, start.plusSeconds(60))

        assertThat(limit.retryAfter(sent, start.plusSeconds(120))).isNull()
    }

    @Test
    fun `at the limit the retry is when the oldest send leaves the window`() {
        val sent = listOf(start, start.plusSeconds(60), start.plusSeconds(120))

        assertThat(limit.retryAfter(sent, start.plusSeconds(600))).isEqualTo(Duration.ofSeconds(3000))
    }

    @Test
    fun `sends outside the window are ignored`() {
        val sent = listOf(start, start.plusSeconds(60), start.plusSeconds(120))

        assertThat(limit.retryAfter(sent, start.plusSeconds(3600))).isNull()
    }

    @Test
    fun `above the limit the retry waits until enough sends have left the window`() {
        val sent = listOf(start, start.plusSeconds(60), start.plusSeconds(120), start.plusSeconds(180))

        assertThat(limit.retryAfter(sent, start.plusSeconds(600))).isEqualTo(Duration.ofSeconds(3060))
    }
}
