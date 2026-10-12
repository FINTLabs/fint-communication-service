package no.novari.communication.email

import org.springframework.boot.context.properties.ConfigurationProperties

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
) {
    override fun toString(): String = "AcsProperties(connectionString=***)"
}
