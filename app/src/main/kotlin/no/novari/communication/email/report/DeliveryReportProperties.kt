package no.novari.communication.email.report

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("communication.email.delivery-reports")
data class DeliveryReportProperties(
    val enabled: Boolean = false,
    val queueUrl: String? = null,
    val region: String = "eu-north-1",
    val pollInterval: Duration = Duration.ofSeconds(10),
    val batchSize: Int = MAX_BATCH_SIZE,
    val visibilityTimeout: Duration = Duration.ofMinutes(5),
    val maxReceiveCount: Int = 5,
    val queueMetricsRefresh: Duration = Duration.ofSeconds(60),
) {
    init {
        require(batchSize in 1..MAX_BATCH_SIZE) {
            "communication.email.delivery-reports.batch-size må være mellom 1 og $MAX_BATCH_SIZE"
        }
        require(maxReceiveCount > 0) { "communication.email.delivery-reports.max-receive-count må være større enn 0" }
        require(visibilityTimeout.isPositive) {
            "communication.email.delivery-reports.visibility-timeout må være større enn 0"
        }
    }

    private companion object {
        const val MAX_BATCH_SIZE = 10
    }
}
