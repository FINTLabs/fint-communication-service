package no.novari.communication.template

data class RenderedEmail(
    val subject: String,
    val body: String,
) {
    override fun toString(): String = "RenderedEmail(subject=***, body=***)"
}
