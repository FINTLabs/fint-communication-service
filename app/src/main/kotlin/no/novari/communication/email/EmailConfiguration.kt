package no.novari.communication.email

import no.novari.communication.message.domain.EmailAddress
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient

@Configuration
@EnableConfigurationProperties(EmailProperties::class)
class EmailConfiguration {
    @Bean
    fun emailAdapter(properties: EmailProperties): EmailAdapter =
        when (properties.provider) {
            EmailProvider.LOGGING -> LoggingEmailAdapter()
            EmailProvider.SES -> sesEmailAdapter(properties)
        }

    private fun sesEmailAdapter(properties: EmailProperties): SesEmailAdapter {
        val sender = properties.sender
        check(!sender.isNullOrBlank()) { "Avsenderadressen ($SENDER_PROPERTY) mangler" }
        check(EmailAddress.isValid(sender)) { "Avsenderadressen ($SENDER_PROPERTY) er ikke en gyldig adresse" }
        val client =
            SesEmailAdapter
                .clientBuilder(properties.ses.region)
                .httpClient(UrlConnectionHttpClient.create())
                .build()
        return SesEmailAdapter(client, sender, properties.ses.configurationSet)
    }

    private companion object {
        const val SENDER_PROPERTY = "communication.email.sender"
    }
}
