package no.novari.communication.retention

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class RetentionCleanupJob(
    private val cleaners: List<ExpiredRowsCleaner>,
    private val clock: Clock,
) {
    private val logger = KotlinLogging.logger {}

    @Scheduled(cron = "\${communication.retention.cleanup-cron}", zone = "Europe/Oslo")
    fun deleteExpiredRows() {
        val now = clock.instant()
        cleaners.forEach { cleaner ->
            try {
                val deleted = cleaner.deleteExpired(now)
                logger.info { "Opprydning ${cleaner.name}: slettet $deleted utløpte rader" }
            } catch (e: Exception) {
                logger.error(e) { "Opprydning ${cleaner.name} feilet" }
            }
        }
    }
}
