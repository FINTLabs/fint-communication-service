package no.novari.communication.message.dispatch

import no.novari.communication.message.domain.MessageChannel
import no.novari.communication.message.domain.MessageId
import no.novari.communication.message.domain.OutgoingMessage
import no.novari.communication.model.Tenant
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class DispatchQueueRepository(
    private val jdbcClient: JdbcClient,
) {
    fun enqueue(
        message: OutgoingMessage,
        payload: ByteArray,
    ) {
        jdbcClient
            .sql(
                """
                INSERT INTO dispatch_queue (message_id, next_attempt_at, payload)
                VALUES (:messageId, :nextAttemptAt, :payload)
                """.trimIndent(),
            ).param("messageId", message.id.value)
            .param("nextAttemptAt", message.receivedAt.toOffsetDateTime())
            .param("payload", payload)
            .update()
    }

    fun claimDue(
        now: Instant,
        lockedUntil: Instant,
        limit: Int,
    ): List<QueuedMessage> =
        jdbcClient
            .sql(
                """
                UPDATE dispatch_queue
                SET attempts = dispatch_queue.attempts + 1, locked_until = :lockedUntil
                FROM message
                WHERE message.message_id = dispatch_queue.message_id
                  AND dispatch_queue.message_id IN (
                    SELECT message_id
                    FROM dispatch_queue
                    WHERE next_attempt_at <= :now AND (locked_until IS NULL OR locked_until <= :now)
                    ORDER BY next_attempt_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING dispatch_queue.message_id, message.tenant, message.channel, message.template_id,
                    dispatch_queue.attempts, dispatch_queue.payload
                """.trimIndent(),
            ).param("now", now.toOffsetDateTime())
            .param("lockedUntil", lockedUntil.toOffsetDateTime())
            .param("limit", limit)
            .query { rs, _ -> rs.toQueuedMessage() }
            .list()

    // attempts skiller dette forsøket fra et senere forsøk på en annen replika etter at leasen gikk ut.
    fun reschedule(
        message: QueuedMessage,
        nextAttemptAt: Instant,
    ): Boolean =
        jdbcClient
            .sql(
                """
                UPDATE dispatch_queue
                SET next_attempt_at = :nextAttemptAt, locked_until = NULL
                WHERE message_id = :messageId AND attempts = :attempts
                """.trimIndent(),
            ).param("nextAttemptAt", nextAttemptAt.toOffsetDateTime())
            .param("messageId", message.id.value)
            .param("attempts", message.attempts)
            .update() == 1

    fun delete(message: QueuedMessage): Boolean =
        jdbcClient
            .sql("DELETE FROM dispatch_queue WHERE message_id = :messageId AND attempts = :attempts")
            .param("messageId", message.id.value)
            .param("attempts", message.attempts)
            .update() == 1

    fun stats(): QueueStats =
        jdbcClient
            .sql(
                """
                SELECT count(*) AS size, min(message.received_at) AS oldest
                FROM dispatch_queue JOIN message USING (message_id)
                """.trimIndent(),
            ).query { rs, _ ->
                QueueStats(rs.getInt("size"), rs.getObject("oldest", OffsetDateTime::class.java)?.toInstant())
            }.single()

    private fun ResultSet.toQueuedMessage(): QueuedMessage =
        QueuedMessage(
            id = MessageId(getObject("message_id", UUID::class.java)),
            tenant = Tenant.valueOf(getString("tenant")),
            channel = MessageChannel.valueOf(getString("channel")),
            templateId = getString("template_id"),
            attempts = getInt("attempts"),
            payload = getBytes("payload"),
        )

    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}

data class QueueStats(
    val size: Int,
    val oldestReceivedAt: Instant?,
)
