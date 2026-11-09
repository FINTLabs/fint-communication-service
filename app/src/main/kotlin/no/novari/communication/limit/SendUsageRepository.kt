package no.novari.communication.limit

import no.novari.communication.message.domain.MessageId
import no.novari.communication.model.Tenant
import no.novari.communication.recipient.RecipientHash
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class SendUsageRepository(
    private val jdbcClient: JdbcClient,
) {
    fun recipientSentTimesSince(
        recipient: RecipientHash,
        since: Instant,
    ): List<Instant> =
        jdbcClient
            .sql(
                """
                SELECT sent_at FROM send_usage
                WHERE recipient_hash = :recipientHash AND sent_at > :since
                ORDER BY sent_at
                """.trimIndent(),
            ).param("recipientHash", recipient.value)
            .param("since", since.toOffsetDateTime())
            .query { rs, _ -> rs.getObject("sent_at", OffsetDateTime::class.java).toInstant() }
            .list()

    fun record(
        messageId: MessageId,
        recipient: RecipientHash,
        tenant: Tenant,
        sentAt: Instant,
    ) {
        jdbcClient
            .sql(
                """
                INSERT INTO send_usage (message_id, recipient_hash, tenant, sent_at)
                VALUES (:messageId, :recipientHash, :tenant, :sentAt)
                """.trimIndent(),
            ).param("messageId", messageId.value)
            .param("recipientHash", recipient.value)
            .param("tenant", tenant.name)
            .param("sentAt", sentAt.toOffsetDateTime())
            .update()
    }

    fun deleteSentBefore(cutoff: Instant): Int =
        jdbcClient
            .sql("DELETE FROM send_usage WHERE sent_at <= :cutoff")
            .param("cutoff", cutoff.toOffsetDateTime())
            .update()

    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}
