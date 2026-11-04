package no.novari.communication.email

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("communication.email")
data class EmailProperties(
    val provider: EmailProvider = EmailProvider.LOGGING,
    val sender: String? = null,
    val ses: SesProperties = SesProperties(),
)

enum class EmailProvider {
    LOGGING,
    SES,
}

data class SesProperties(
    val region: String = "eu-north-1",
    val configurationSet: String? = null,
)
