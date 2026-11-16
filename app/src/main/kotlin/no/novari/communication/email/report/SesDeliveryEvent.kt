package no.novari.communication.email.report

import no.novari.communication.model.FailureReason
import no.novari.communication.model.MessageStatus

enum class SesDeliveryEvent(
    val value: String,
    val messageStatus: MessageStatus?,
    val failureReason: FailureReason? = null,
) {
    DELIVERED("Delivery", MessageStatus.DELIVERED),
    BOUNCED("Bounce/Permanent", MessageStatus.BOUNCED),
    SUPPRESSED("Bounce/Suppressed", MessageStatus.SUPPRESSED),
    SOFT_BOUNCED("Bounce/Transient", MessageStatus.FAILED, FailureReason.DELIVERY_FAILED),
    REJECTED("Reject", MessageStatus.FAILED, FailureReason.REJECTED),
    COMPLAINED("Complaint", null),
    COMPLAINT_SUPPRESSED("Complaint/Suppressed", MessageStatus.SUPPRESSED),
    ;

    val metricValue: String
        get() = messageStatus?.name ?: name
}
