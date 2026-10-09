package no.novari.communication.limit

import no.novari.communication.message.domain.MessageId
import no.novari.communication.model.Tenant
import no.novari.communication.recipient.RecipientHash
import org.springframework.dao.CannotAcquireLockException
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class SendUsageRepository(
    private val jdbcClient: JdbcClient,
) {
    fun setLockTimeout(timeout: Duration) {
        jdbcClient
            .sql("SELECT set_config('lock_timeout', :timeout, true)")
            .param("timeout", "${timeout.toMillis()}ms")
            .query {}
    }

    fun lockRecipient(recipient: RecipientHash) {
        acquireLock(jdbcClient.sql("SELECT pg_advisory_xact_lock(:key)").param("key", lockKey(recipient)))
    }

    // Spring oversetter ikke lock_not_available, så uten dette ville lock timeout gitt 500 i stedet for 503.
    private fun acquireLock(statement: JdbcClient.StatementSpec) {
        try {
            statement.query {}
        } catch (exception: UncategorizedSQLException) {
            if (exception.sqlException?.sqlState != LOCK_NOT_AVAILABLE_SQL_STATE) throw exception
            throw CannotAcquireLockException("Fikk ikke låsen innen lock_timeout", exception)
        }
    }

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

    private fun lockKey(recipient: RecipientHash): Long = java.lang.Long.parseUnsignedLong(recipient.value.take(16), 16)

    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

    private companion object {
        const val LOCK_NOT_AVAILABLE_SQL_STATE = "55P03"
    }
}
