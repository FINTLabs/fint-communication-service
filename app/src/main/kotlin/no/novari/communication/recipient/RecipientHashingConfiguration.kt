package no.novari.communication.recipient

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(RecipientHashingProperties::class)
class RecipientHashingConfiguration {
    @Bean
    fun recipientHasher(properties: RecipientHashingProperties): RecipientHasher =
        RecipientHasher.fromBase64Key(properties.key)
}
