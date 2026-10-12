package no.novari.communication.email.report

import no.novari.communication.model.FailureReason
import no.novari.communication.model.MessageStatus

enum class AcsDeliveryStatus(
    val value: String,
    val messageStatus: MessageStatus?,
    val failureReason: FailureReason? = null,
    val blocksRecipient: Boolean = false,
) {
    DELIVERED("Delivered", MessageStatus.DELIVERED),
    BOUNCED("Bounced", MessageStatus.BOUNCED, blocksRecipient = true),
    SUPPRESSED("Suppressed", MessageStatus.SUPPRESSED, blocksRecipient = true),
    FILTERED_SPAM("FilteredSpam", MessageStatus.FILTERED_SPAM),
    QUARANTINED("Quarantined", MessageStatus.FILTERED_SPAM),
    FAILED("Failed", MessageStatus.FAILED, FailureReason.DELIVERY_FAILED),
    EXPANDED("Expanded", null),
    ;

    val metricValue: String
        get() = messageStatus?.name ?: name

    companion object {
        fun fromValue(value: String): AcsDeliveryStatus? = entries.firstOrNull { it.value == value }
    }
}
