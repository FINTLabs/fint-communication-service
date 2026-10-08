package no.novari.communication.recipient

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.Base64

@Configuration
@EnableConfigurationProperties(RecipientHashingProperties::class)
class RecipientHashingConfiguration {
    @Bean
    fun recipientHasher(properties: RecipientHashingProperties): RecipientHasher =
        RecipientHasher(decodeKey(properties.key))

    private fun decodeKey(key: String?): ByteArray {
        check(!key.isNullOrBlank()) { "$KEY_DESCRIPTION mangler" }
        val bytes =
            try {
                Base64.getDecoder().decode(key.trim())
            } catch (_: IllegalArgumentException) {
                throw IllegalStateException("$KEY_DESCRIPTION er ikke gyldig base64")
            }
        check(bytes.size >= RecipientHasher.MIN_KEY_BYTES) {
            "$KEY_DESCRIPTION må være minst ${RecipientHasher.MIN_KEY_BYTES} bytes etter base64-dekoding"
        }
        return bytes
    }

    private companion object {
        const val KEY_DESCRIPTION =
            "Nøkkelen for mottaker-hashing (communication.recipient-hashing.key / " +
                "COMMUNICATION_RECIPIENT_HASHING_KEY)"
    }
}
