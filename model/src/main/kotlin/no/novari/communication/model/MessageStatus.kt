package no.novari.communication.model

enum class MessageStatus {
    RECEIVED,
    SENT,
    FAILED,
    DELIVERED,
    BOUNCED,
    SUPPRESSED,
    FILTERED_SPAM,
}
