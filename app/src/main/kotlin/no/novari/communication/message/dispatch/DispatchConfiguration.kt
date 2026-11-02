package no.novari.communication.message.dispatch

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.json.JsonMapper

@Configuration
@EnableConfigurationProperties(DispatchProperties::class)
class DispatchConfiguration {
    @Bean
    fun payloadCodec(
        properties: DispatchProperties,
        jsonMapper: JsonMapper,
    ): PayloadCodec = PayloadCodec(PayloadCipher.fromBase64Key(properties.encryptionKey), jsonMapper)
}
