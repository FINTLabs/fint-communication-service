package no.novari.communication.retention

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RetentionCleanupJobTest {
    private val now = Instant.parse("2026-10-08T01:15:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `every cleaner is called with the current time`() {
        val first = RecordingCleaner("first")
        val second = RecordingCleaner("second")

        RetentionCleanupJob(listOf(first, second), clock).deleteExpiredRows()

        assertThat(first.calls).containsExactly(now)
        assertThat(second.calls).containsExactly(now)
    }

    @Test
    fun `a failing cleaner does not stop the others`() {
        val failing = RecordingCleaner("failing", failure = IllegalStateException("database nede"))
        val next = RecordingCleaner("next")

        RetentionCleanupJob(listOf(failing, next), clock).deleteExpiredRows()

        assertThat(failing.calls).containsExactly(now)
        assertThat(next.calls).containsExactly(now)
    }

    @Test
    fun `runs without cleaners`() {
        RetentionCleanupJob(emptyList(), clock).deleteExpiredRows()
    }

    private class RecordingCleaner(
        override val name: String,
        private val failure: Exception? = null,
    ) : ExpiredRowsCleaner {
        val calls = mutableListOf<Instant>()

        override fun deleteExpired(now: Instant): Int {
            calls += now
            failure?.let { throw it }
            return 0
        }
    }
}
