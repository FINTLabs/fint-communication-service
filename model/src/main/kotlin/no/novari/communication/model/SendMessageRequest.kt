package no.novari.communication.model

data class SendMessageRequest(
    val tenant: String,
    val email: EmailMessage? = null,
)
