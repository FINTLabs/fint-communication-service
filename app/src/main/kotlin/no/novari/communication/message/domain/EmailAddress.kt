package no.novari.communication.message.domain

object EmailAddress {
    const val MAX_LENGTH = 254

    private val PATTERN =
        Regex(
            """^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$""",
        )

    fun isValid(address: String): Boolean = address.length <= MAX_LENGTH && PATTERN.matches(address)
}
