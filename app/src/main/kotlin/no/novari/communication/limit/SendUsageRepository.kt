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

    // Spring oversetter ikke lock_not_available, så uten dette ville lock timeout gitt 500 i stedet for 503.
    fun lock() {
        try {
            jdbcClient.sql("SELECT pg_advisory_xact_lock(:key, 0)").param("key", LOCK_KEY).query {}
        } catch (exception: UncategorizedSQLException) {
            if (exception.sqlException?.sqlState != LOCK_NOT_AVAILABLE_SQL_STATE) throw exception
            throw CannotAcquireLockException("Fikk ikke låsen innen lock_timeout", exception)
        }
    }

    fun usageSince(since: Instant): List<SendUsage> =
        jdbcClient
            .sql("SELECT recipient_hash, tenant, sent_at FROM send_usage WHERE sent_at > :since ORDER BY sent_at")
            .param("since", since.toOffsetDateTime())
            .query { rs, _ ->
                SendUsage(
                    RecipientHash(rs.getString("recipient_hash")),
                    Tenant.valueOf(rs.getString("tenant")),
                    rs.getObject("sent_at", OffsetDateTime::class.java).toInstant(),
                )
            }.list()

    fun countPerTenantSince(
        hourAgo: Instant,
        dayAgo: Instant,
    ): Map<Tenant, TenantUsage> =
        jdbcClient
            .sql(
                """
                SELECT tenant, count(*) FILTER (WHERE sent_at > :hourAgo) AS last_hour, count(*) AS last_day
                FROM send_usage
                WHERE sent_at > :dayAgo
                GROUP BY tenant
                """.trimIndent(),
            ).param("hourAgo", hourAgo.toOffsetDateTime())
            .param("dayAgo", dayAgo.toOffsetDateTime())
            .query { rs, _ ->
                Tenant.valueOf(rs.getString("tenant")) to TenantUsage(rs.getInt("last_hour"), rs.getInt("last_day"))
            }.list()
            .toMap()

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

    private companion object {
        const val LOCK_KEY = 1
        const val LOCK_NOT_AVAILABLE_SQL_STATE = "55P03"
    }
}

data class SendUsage(
    val recipient: RecipientHash,
    val tenant: Tenant,
    val sentAt: Instant,
)
