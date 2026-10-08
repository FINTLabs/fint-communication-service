package no.novari.communication.message.domain

sealed interface MessagePayload {
    val channel: MessageChannel
    val templateId: String
}
