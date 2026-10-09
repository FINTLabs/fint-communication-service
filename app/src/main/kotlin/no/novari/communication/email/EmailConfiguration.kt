package no.novari.communication.email

import no.novari.communication.message.domain.EmailAddress
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(EmailProperties::class)
class EmailConfiguration {
    @Bean
    fun emailAdapter(properties: EmailProperties): EmailAdapter =
        when (properties.provider) {
            EmailProvider.LOGGING -> LoggingEmailAdapter()
            EmailProvider.ACS -> acsEmailAdapter(properties)
        }

    private fun acsEmailAdapter(properties: EmailProperties): AcsEmailAdapter {
        val connectionString = properties.acs.connectionString
        check(!connectionString.isNullOrBlank()) { "$CONNECTION_STRING_DESCRIPTION mangler" }
        val sender = properties.sender
        check(!sender.isNullOrBlank()) { "Avsenderadressen ($SENDER_PROPERTY) mangler" }
        check(EmailAddress.isValid(sender)) { "Avsenderadressen ($SENDER_PROPERTY) er ikke en gyldig adresse" }
        val client =
            try {
                AcsEmailAdapter.clientBuilder(connectionString).buildClient()
            } catch (_: IllegalArgumentException) {
                throw IllegalStateException("$CONNECTION_STRING_DESCRIPTION er ugyldig")
            }
        return AcsEmailAdapter(client, sender, properties.acs.operationTimeout)
    }

    companion object {
        const val CONNECTION_STRING_ENVIRONMENT_VARIABLE = "COMMUNICATION_EMAIL_ACS_CONNECTION_STRING"
        private const val SENDER_PROPERTY = "communication.email.sender"
        private const val CONNECTION_STRING_DESCRIPTION =
            "Connection string for ACS (communication.email.acs.connection-string / " +
                "$CONNECTION_STRING_ENVIRONMENT_VARIABLE)"
    }
}
