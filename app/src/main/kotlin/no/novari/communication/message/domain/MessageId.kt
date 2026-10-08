package no.novari.communication.message.domain

import java.util.UUID

@JvmInline
value class MessageId(
    val value: UUID,
) {
    companion object {
        fun generate(): MessageId = MessageId(UUID.randomUUID())
    }
}
