package no.novari.communication.recipient

@JvmInline
value class RecipientHash(
    val value: String,
) {
    override fun toString(): String = "RecipientHash(***)"
}
