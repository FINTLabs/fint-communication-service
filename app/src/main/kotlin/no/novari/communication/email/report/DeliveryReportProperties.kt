package no.novari.communication.email.report

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("communication.email.delivery-reports")
data class DeliveryReportProperties(
    val enabled: Boolean = false,
    val queueUrl: String? = null,
    val region: String = "eu-north-1",
    val pollInterval: Duration = Duration.ofSeconds(10),
)
