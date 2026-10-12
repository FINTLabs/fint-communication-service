package no.novari.communication.email.report

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("communication.email.delivery-reports")
class DeliveryReportProperties(
    val enabled: Boolean = false,
    val connectionString: String? = null,
    val queueName: String = "email-delivery-reports",
    val pollInterval: Duration = Duration.ofSeconds(10),
    val batchSize: Int = MAX_BATCH_SIZE,
    val visibilityTimeout: Duration = Duration.ofMinutes(5),
    val maxDequeueCount: Int = 5,
    val queueMetricsRefresh: Duration = Duration.ofSeconds(60),
    val blockFor: Duration = Duration.ofDays(30),
) {
    init {
        require(batchSize in 1..MAX_BATCH_SIZE) {
            "communication.email.delivery-reports.batch-size må være mellom 1 og $MAX_BATCH_SIZE"
        }
        require(maxDequeueCount > 0) { "communication.email.delivery-reports.max-dequeue-count må være større enn 0" }
        require(visibilityTimeout.isPositive) {
            "communication.email.delivery-reports.visibility-timeout må være større enn 0"
        }
        require(blockFor.isPositive) { "communication.email.delivery-reports.block-for må være større enn 0" }
    }

    override fun toString(): String =
        "DeliveryReportProperties(enabled=$enabled, connectionString=***, queueName=$queueName, " +
            "pollInterval=$pollInterval, batchSize=$batchSize, visibilityTimeout=$visibilityTimeout, " +
            "maxDequeueCount=$maxDequeueCount, queueMetricsRefresh=$queueMetricsRefresh, blockFor=$blockFor)"

    private companion object {
        const val MAX_BATCH_SIZE = 32
    }
}
