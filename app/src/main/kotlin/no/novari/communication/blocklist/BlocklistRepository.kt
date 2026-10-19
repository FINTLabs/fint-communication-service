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

    fun deleteExpired(now: Instant): Int =
        jdbcClient
            .sql("DELETE FROM recipient_blocklist WHERE expires_at <= :now")
            .param("now", now.toOffsetDateTime())
            .update()

    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}
