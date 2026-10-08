package no.novari.communication.retention

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("communication.retention")
data class RetentionProperties(
    val metadata: Duration,
    val cleanupCron: String,
)
