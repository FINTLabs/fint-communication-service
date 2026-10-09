package no.novari.communication.blocklist

import no.novari.communication.retention.ExpiredRowsCleaner
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class BlocklistCleaner(
    private val repository: BlocklistRepository,
) : ExpiredRowsCleaner {
    override val name = "recipient_blocklist"

    override fun deleteExpired(now: Instant): Int = repository.deleteExpired(now)
}
