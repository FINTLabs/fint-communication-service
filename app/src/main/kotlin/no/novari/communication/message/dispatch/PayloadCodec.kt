package no.novari.communication.message.dispatch

import no.novari.communication.message.domain.EmailPayload
import no.novari.communication.message.domain.MessageId
import no.novari.communication.message.domain.MessagePayload
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.nio.ByteBuffer

class PayloadCodec(
    private val cipher: PayloadCipher,
    private val jsonMapper: JsonMapper,
) {
    fun encode(
        id: MessageId,
        payload: MessagePayload,
    ): ByteArray {
        val content =
            when (payload) {
                is EmailPayload -> StoredEmail(payload.to, payload.subject, payload.body, payload.replyTo)
            }
        return cipher.encrypt(jsonMapper.writeValueAsBytes(content), id.toBytes())
    }

    fun decodeEmail(
        id: MessageId,
        templateId: String,
        encoded: ByteArray,
    ): EmailPayload {
        val content = jsonMapper.readValue<StoredEmail>(cipher.decrypt(encoded, id.toBytes()))
        return EmailPayload(templateId, content.to, content.subject, content.body, content.replyTo)
    }

    private fun MessageId.toBytes(): ByteArray =
        ByteBuffer
            .allocate(UUID_BYTES)
            .putLong(value.mostSignificantBits)
            .putLong(value.leastSignificantBits)
            .array()

    private data class StoredEmail(
        val to: String,
        val subject: String,
        val body: String,
        val replyTo: String?,
    )

    private companion object {
        const val UUID_BYTES = 16
    }
}
