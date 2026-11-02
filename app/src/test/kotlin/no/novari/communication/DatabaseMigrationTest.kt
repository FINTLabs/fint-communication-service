package no.novari.communication

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient

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
}
