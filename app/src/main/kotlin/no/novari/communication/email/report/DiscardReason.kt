package no.novari.communication.email.report

enum class DiscardReason(
    val value: String,
) {
    INVALID("invalid"),
    UNSUPPORTED_TYPE("unsupported-type"),
    UNKNOWN_MESSAGE("unknown-message"),
    POISON("poison"),
}
