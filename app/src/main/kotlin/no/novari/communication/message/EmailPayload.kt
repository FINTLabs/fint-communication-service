package no.novari.communication.message

data class EmailPayload(
    val to: String,
    val subject: String,
    val body: String,
    val replyTo: String? = null,
) : MessagePayload {
    override val channel: MessageChannel
        get() = MessageChannel.EMAIL

    init {
        require(to.isNotBlank()) { "Mottaker (to) kan ikke være tom" }
        require(subject.isNotBlank()) { "Emne (subject) kan ikke være tomt" }
        require(body.isNotBlank()) { "Innhold (body) kan ikke være tomt" }
        require(replyTo == null || replyTo.isNotBlank()) { "Svaradresse (replyTo) kan ikke være tom når den er satt" }
    }

    // Felter kan inneholde personopplysninger og skal ikke havne i logger via toString.
    override fun toString(): String = "EmailPayload(to=$MASK, subject=$MASK, body=$MASK, replyTo=$replyTo)"

    private companion object {
        const val MASK = "***"
    }
}
