package no.novari.communication.message

import no.novari.communication.retention.ExpiredRowsCleaner
import no.novari.communication.retention.RetentionProperties
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class MessageCleaner(
    private val store: DatabaseMessageStore,
    private val retention: RetentionProperties,
) : ExpiredRowsCleaner {
    override val name = "message"

    override fun deleteExpired(now: Instant): Int = store.deleteReceivedBefore(now - retention.metadata)
}
