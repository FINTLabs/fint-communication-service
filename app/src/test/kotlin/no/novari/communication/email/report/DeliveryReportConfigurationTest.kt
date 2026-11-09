package no.novari.communication.email.report

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.novari.communication.message.DatabaseMessageStore
import no.novari.communication.message.MessageStore
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
            .withBean(MessageStore::class.java, { DatabaseMessageStore(unusedJdbcClient) })
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
    fun `starts the poller when enabled with a queue url`() {
        contextRunner
            .withPropertyValues(
                "communication.email.delivery-reports.enabled=true",
                "communication.email.delivery-reports.queue-url=$QUEUE_URL",
            ).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(DeliveryReportPoller::class.java)
            }
    }

    @Test
    fun `fails to start when enabled without a queue url`() {
        contextRunner.withPropertyValues("communication.email.delivery-reports.enabled=true").run { context ->
            assertThat(context).hasFailed()
            assertThat(rootCauseMessage(context.startupFailure))
                .contains("communication.email.delivery-reports.queue-url")
                .contains("mangler")
        }
    }

    private fun rootCauseMessage(failure: Throwable?): String =
        generateSequence(failure) { it.cause }.last().message.orEmpty()

    private companion object {
        const val QUEUE_URL = "https://sqs.eu-north-1.amazonaws.com/123456789012/email-delivery-reports"
    }
}
