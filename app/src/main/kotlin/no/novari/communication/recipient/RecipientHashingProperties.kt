package no.novari.communication.recipient

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("communication.recipient-hashing")
class RecipientHashingProperties(
    val key: String?,
) {
    override fun toString(): String = "RecipientHashingProperties(key=***)"
}
