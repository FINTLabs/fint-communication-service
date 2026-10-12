package no.novari.communication.email

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("communication.email")
data class EmailProperties(
    val provider: EmailProvider = EmailProvider.LOGGING,
    val sender: String? = null,
    val acs: AcsProperties = AcsProperties(),
)

enum class EmailProvider {
    LOGGING,
    ACS,
}

class AcsProperties(
    val connectionString: String? = null,
    val operationTimeout: Duration = Duration.ofMinutes(2),
) {
    override fun toString(): String = "AcsProperties(connectionString=***, operationTimeout=$operationTimeout)"
}
