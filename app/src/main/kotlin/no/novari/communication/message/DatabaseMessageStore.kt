package no.novari.communication.message

import no.novari.communication.message.domain.MessageChannel
import no.novari.communication.message.domain.MessageId
import no.novari.communication.message.domain.OutgoingMessage
import no.novari.communication.model.FailureReason
import no.novari.communication.model.MessageStatus
import no.novari.communication.model.Tenant
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Component
class DatabaseMessageStore(
    private val jdbcClient: JdbcClient,
) : MessageStore {
    override fun save(message: OutgoingMessage) {
        jdbcClient
            .sql(
                """
                INSERT INTO message (message_id, tenant, channel, template_id, status, received_at, updated_at)
                VALUES (:messageId, :tenant, :channel, :templateId, :status, :receivedAt, :receivedAt)
                """.trimIndent(),
            ).param("messageId", message.id.value)
            .param("tenant", message.tenant.name)
            .param("channel", message.channel.name)
            .param("templateId", message.payload.templateId)
            .param("status", message.status.name)
            .param("receivedAt", message.receivedAt.toOffsetDateTime())
            .update()
    }

    override fun find(id: MessageId): StoredMessage? =
        jdbcClient
            .sql(
                """
                SELECT message_id, tenant, channel, template_id, status, failure_reason, received_at, updated_at
                FROM message
                WHERE message_id = :messageId
                """.trimIndent(),
            ).param("messageId", id.value)
            .query { rs, _ -> rs.toStoredMessage() }
            .optional()
            .orElse(null)

    override fun complete(
        id: MessageId,
        status: MessageStatus,
        failureReason: FailureReason?,
        at: Instant,
    ) {
        jdbcClient
            .sql(
                """
                UPDATE message
                SET status = :status, failure_reason = :failureReason, updated_at = :updatedAt
                WHERE message_id = :messageId AND status = :received
                """.trimIndent(),
            ).param("status", status.name)
            .param("failureReason", failureReason?.name)
            .param("updatedAt", at.toOffsetDateTime())
            .param("messageId", id.value)
            .param("received", MessageStatus.RECEIVED.name)
            .update()
    }

    fun deleteReceivedBefore(cutoff: Instant): Int =
        jdbcClient
            .sql(
                """
                DELETE FROM message
                WHERE received_at < :cutoff
                  AND NOT EXISTS (SELECT 1 FROM dispatch_queue WHERE dispatch_queue.message_id = message.message_id)
                """.trimIndent(),
            ).param("cutoff", cutoff.toOffsetDateTime())
            .update()

    private fun ResultSet.toStoredMessage(): StoredMessage =
        StoredMessage(
            id = MessageId(getObject("message_id", UUID::class.java)),
            tenant = Tenant.valueOf(getString("tenant")),
            channel = MessageChannel.valueOf(getString("channel")),
            templateId = getString("template_id"),
            status = MessageStatus.valueOf(getString("status")),
            failureReason = getString("failure_reason")?.let(FailureReason::valueOf),
            receivedAt = getObject("received_at", OffsetDateTime::class.java).toInstant(),
            updatedAt = getObject("updated_at", OffsetDateTime::class.java).toInstant(),
        )

    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}
