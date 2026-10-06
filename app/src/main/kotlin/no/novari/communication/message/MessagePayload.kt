package no.novari.communication.message

sealed interface MessagePayload {
    val channel: MessageChannel
}
