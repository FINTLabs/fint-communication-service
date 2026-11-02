package no.novari.communication.email

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("communication.email")
data class EmailProperties(
    val provider: EmailProvider = EmailProvider.LOGGING,
)

enum class EmailProvider {
    LOGGING,
}
