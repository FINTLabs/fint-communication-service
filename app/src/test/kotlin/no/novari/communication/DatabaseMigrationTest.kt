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
}
