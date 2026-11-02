package no.novari.communication.email.report

import io.micrometer.core.instrument.MeterRegistry
import no.novari.communication.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

@IntegrationTest
@Import(DeliveryReportQueueUnavailableTest.UnreachableQueue::class)
@TestPropertySource(
    properties = [
        "communication.email.delivery-reports.enabled=true",
        "communication.email.delivery-reports.poll-interval=1h",
        "communication.email.delivery-reports.queue-metrics-refresh=1h",
        "communication.email.delivery-reports.queue-url=$UNREACHABLE_QUEUE_URL",
    ],
)
class DeliveryReportQueueUnavailableTest {
    @Autowired
    lateinit var poller: DeliveryReportPoller

    @Autowired
    lateinit var queueMetrics: DeliveryReportQueueMetrics

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Test
    fun `an unreachable queue is counted without failing the poll or readiness`() {
        val before = pollFailures()

        poller.poll()
        queueMetrics.refresh()

        assertThat(pollFailures() - before).isGreaterThanOrEqualTo(1.0)
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .build()
            .get("/actuator/health/readiness")
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("UP") }
            }
    }

    private fun pollFailures(): Double =
        meterRegistry.find(DeliveryReportMetrics.POLL_FAILED_METRIC).counter()?.count() ?: 0.0

    @TestConfiguration(proxyBeanMethods = false)
    class UnreachableQueue {
        @Bean
        @Primary
        fun unreachableDeliveryReportQueue(): DeliveryReportQueue =
            SqsDeliveryReportQueue(ElasticMqTestConfiguration.sqsClient(UNREACHABLE_ENDPOINT), UNREACHABLE_QUEUE_URL)
    }
}

private const val UNREACHABLE_ENDPOINT = "http://127.0.0.1:1"
private const val UNREACHABLE_QUEUE_URL = "$UNREACHABLE_ENDPOINT/000000000000/email-delivery-reports"
