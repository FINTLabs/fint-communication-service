package no.novari.communication.model

data class SendMessageRequest(
    val tenant: Tenant,
    val message: Message,
)
