package no.novari.communication.model

data class EmailMessage(
    val to: String,
    val subject: String,
    val body: String,
    val replyTo: String? = null,
)
