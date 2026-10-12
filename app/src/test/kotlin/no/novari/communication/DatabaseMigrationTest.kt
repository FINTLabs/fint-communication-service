package no.novari.communication

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

@IntegrationTest
class DatabaseMigrationTest {
    @Autowired
    lateinit var jdbcClient: JdbcClient

    @Test
    fun `flyway applies the baseline migration at startup`() {
        val applied =
            jdbcClient
                .sql("SELECT version FROM flyway_schema_history WHERE success")
                .query(String::class.java)
                .list()

        assertThat(applied).contains("1")
    }

    @Test
    fun `send_usage has indexes for counting per recipient, per tenant and in total`() {
        val indexes =
            jdbcClient
                .sql("SELECT indexname FROM pg_indexes WHERE tablename = 'send_usage'")
                .query(String::class.java)
                .list()

        assertThat(indexes).contains(
            "send_usage_recipient_sent_at",
            "send_usage_tenant_sent_at",
            "send_usage_sent_at",
        )
    }

    @Test
    fun `recipient_blocklist has a primary key per hash and reason and an index for the cleanup`() {
        val indexes =
            jdbcClient
                .sql("SELECT indexname FROM pg_indexes WHERE tablename = 'recipient_blocklist'")
                .query(String::class.java)
                .list()

        assertThat(indexes).contains("recipient_blocklist_pkey", "recipient_blocklist_expires_at")
    }

    @Test
    fun `dispatch_queue has an index for finding due messages`() {
        val indexes =
            jdbcClient
                .sql("SELECT indexname FROM pg_indexes WHERE tablename = 'dispatch_queue'")
                .query(String::class.java)
                .list()

        assertThat(indexes).contains("dispatch_queue_pkey", "dispatch_queue_next_attempt_at")
    }

    @Test
    fun `message has an index for the cleanup and the queue refers to it`() {
        val indexes =
            jdbcClient
                .sql("SELECT indexname FROM pg_indexes WHERE tablename = 'message'")
                .query(String::class.java)
                .list()
        val constraints =
            jdbcClient
                .sql(
                    "SELECT conname FROM pg_constraint WHERE conrelid IN ('message'::regclass, 'dispatch_queue'::regclass)",
                ).query(String::class.java)
                .list()

        assertThat(indexes).contains("message_pkey", "message_received_at")
        assertThat(constraints).contains(
            "message_status",
            "message_failure_reason",
            "message_failure_reason_when_failed",
            "dispatch_queue_message",
        )
    }

    @Test
    fun `a failed message must have a reason and only a failed message can have one`() {
        assertThatThrownBy { insertMessage("FAILED", null) }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertMessage(
                "SENT",
                "REJECTED",
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertMessage(
                "PROCESSING",
                null,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `a queued message must exist in message`() {
        assertThatThrownBy {
            jdbcClient
                .sql("INSERT INTO dispatch_queue (message_id, next_attempt_at, payload) VALUES (:id, now(), '\\x00')")
                .param("id", UUID.randomUUID())
                .update()
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    private fun insertMessage(
        status: String,
        failureReason: String?,
    ) = jdbcClient
        .sql(
            """
            INSERT INTO message (message_id, tenant, channel, template_id, status, failure_reason, received_at, updated_at)
            VALUES (:id, 'ROGALAND', 'EMAIL', 'team/varsel', :status, :failureReason, now(), now())
            """.trimIndent(),
        ).param("id", UUID.randomUUID())
        .param("status", status)
        .param("failureReason", failureReason)
        .update()
}
