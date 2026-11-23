package no.novari.communication.email

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
        }
}
