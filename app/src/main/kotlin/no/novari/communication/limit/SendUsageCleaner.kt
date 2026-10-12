package no.novari.communication.limit

import no.novari.communication.retention.ExpiredRowsCleaner
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class SendUsageCleaner(
    private val repository: SendUsageRepository,
) : ExpiredRowsCleaner {
    override val name = "send_usage"

    override fun deleteExpired(now: Instant): Int = repository.deleteSentBefore(now - DatabaseSendLimiter.DAY)
}
