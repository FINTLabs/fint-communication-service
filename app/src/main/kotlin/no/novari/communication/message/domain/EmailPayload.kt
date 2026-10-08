package no.novari.communication.message.domain

data class EmailPayload(
    override val templateId: String,
    val to: String,
    val subject: String,
    val body: String,
    val replyTo: String? = null,
) : MessagePayload {
    override val channel: MessageChannel
        get() = MessageChannel.EMAIL

    init {
        require(templateId.isNotBlank()) { "Mal (templateId) kan ikke være tom" }
        require(to.isNotBlank()) { "Mottaker (to) kan ikke være tom" }
        require(subject.isNotBlank()) { "Emne (subject) kan ikke være tomt" }
        require(body.isNotBlank()) { "Innhold (body) kan ikke være tomt" }
        require(replyTo == null || replyTo.isNotBlank()) { "Svaradresse (replyTo) kan ikke være tom når den er satt" }
    }

    override fun toString(): String =
        "EmailPayload(templateId=$templateId, to=$MASK, subject=$MASK, body=$MASK, replyTo=$replyTo)"

    private companion object {
        const val MASK = "***"
    }
}
