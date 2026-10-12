package no.novari.communication.email.report

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.novari.communication.TEST_RECIPIENT_HASHING_KEY
import no.novari.communication.blocklist.BlocklistRepository
import no.novari.communication.message.DatabaseMessageStore
import no.novari.communication.message.MessageStore
import no.novari.communication.recipient.RecipientHasher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.springframework.transaction.support.TransactionOperations
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

class DeliveryReportConfigurationTest {
    private val unusedJdbcClient = JdbcClient.create(SimpleDriverDataSource())
    private val contextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(DeliveryReportConfiguration::class.java)
            .withBean(JsonMapper::class.java, { JsonMapper.builder().build() })
            .withBean(RecipientHasher::class.java, { RecipientHasher.fromBase64Key(TEST_RECIPIENT_HASHING_KEY) })
            .withBean(MessageStore::class.java, { DatabaseMessageStore(unusedJdbcClient) })
            .withBean(BlocklistRepository::class.java, { BlocklistRepository(unusedJdbcClient) })
            .withBean(TransactionOperations::class.java, { TransactionOperations.withoutTransaction() })
            .withBean(Clock::class.java, { Clock.systemUTC() })
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })

    @Test
    fun `receiving delivery reports is off by default`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(DeliveryReportPoller::class.java)
            assertThat(context).doesNotHaveBean(DeliveryReportQueue::class.java)
        }
    }

    @Test
    fun `starts the poller when enabled with a connection string`() {
        contextRunner
            .withPropertyValues(
                "communication.email.delivery-reports.enabled=true",
                "communication.email.delivery-reports.connection-string=$CONNECTION_STRING",
            ).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(DeliveryReportPoller::class.java)
            }
    }

    @Test
    fun `fails to start when enabled without a connection string`() {
        contextRunner.withPropertyValues("communication.email.delivery-reports.enabled=true").run { context ->
            assertThat(context).hasFailed()
            assertThat(rootCauseMessage(context.startupFailure))
                .contains(DeliveryReportConfiguration.CONNECTION_STRING_ENVIRONMENT_VARIABLE)
                .contains("mangler")
        }
    }

    @Test
    fun `fails to start with an invalid connection string without revealing it`() {
        contextRunner
            .withPropertyValues(
                "communication.email.delivery-reports.enabled=true",
                "communication.email.delivery-reports.connection-string=AccountName=konto;hemmelig=verdi",
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(rootCauseMessage(context.startupFailure))
                    .contains(DeliveryReportConfiguration.CONNECTION_STRING_ENVIRONMENT_VARIABLE)
                    .doesNotContain("hemmelig")
            }
    }

    @Test
    fun `properties do not expose the connection string`() {
        assertThat(
            DeliveryReportProperties(connectionString = CONNECTION_STRING).toString(),
        ).doesNotContain(ACCOUNT_KEY)
    }

    private fun rootCauseMessage(failure: Throwable?): String =
        generateSequence(failure) { it.cause }.last().message.orEmpty()

    private companion object {
        const val ACCOUNT_KEY = "aGVtbWVsaWctbsO4a2tlbC1mb3Ita8O4ZW4="
        const val CONNECTION_STRING =
            "DefaultEndpointsProtocol=https;AccountName=konto;AccountKey=$ACCOUNT_KEY;EndpointSuffix=core.windows.net"
    }
}
