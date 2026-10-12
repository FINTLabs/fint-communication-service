package no.novari.communication.blocklist

import no.novari.communication.recipient.RecipientHash
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class BlocklistRepository(
    private val jdbcClient: JdbcClient,
) {
    fun isBlocked(
        recipient: RecipientHash,
        now: Instant,
    ): Boolean =
        jdbcClient
            .sql(
                """
                SELECT EXISTS (
                    SELECT 1 FROM recipient_blocklist
                    WHERE recipient_hash = :recipientHash AND (expires_at IS NULL OR expires_at > :now)
                )
                """.trimIndent(),
            ).param("recipientHash", recipient.value)
            .param("now", now.toOffsetDateTime())
            .query(Boolean::class.java)
            .single()

    fun blockHardBounce(
        recipient: RecipientHash,
        source: BlockSource,
        expiresAt: Instant,
    ) {
        jdbcClient
            .sql(
                """
                INSERT INTO recipient_blocklist (recipient_hash, reason, source, expires_at)
                VALUES (:recipientHash, :reason, :source, :expiresAt)
                ON CONFLICT (recipient_hash, reason)
                    DO UPDATE SET expires_at = GREATEST(recipient_blocklist.expires_at, EXCLUDED.expires_at)
                """.trimIndent(),
            ).param("recipientHash", recipient.value)
            .param("reason", BlockReason.HARD_BOUNCE.name)
            .param("source", source.name)
            .param("expiresAt", expiresAt.toOffsetDateTime())
            .update()
    }

    fun deleteExpired(now: Instant): Int =
        jdbcClient
            .sql("DELETE FROM recipient_blocklist WHERE expires_at <= :now")
            .param("now", now.toOffsetDateTime())
            .update()

    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}
