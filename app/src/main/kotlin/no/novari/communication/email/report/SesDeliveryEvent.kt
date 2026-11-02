package no.novari.communication.email.report

import no.novari.communication.blocklist.BlockReason
import no.novari.communication.model.FailureReason
import no.novari.communication.model.MessageStatus

enum class SesDeliveryEvent(
    val value: String,
    val messageStatus: MessageStatus?,
    val failureReason: FailureReason? = null,
    val blocks: BlockReason? = null,
) {
    DELIVERED("Delivery", MessageStatus.DELIVERED),
    BOUNCED("Bounce/Permanent", MessageStatus.BOUNCED, blocks = BlockReason.HARD_BOUNCE),
    SUPPRESSED("Bounce/Suppressed", MessageStatus.SUPPRESSED, blocks = BlockReason.HARD_BOUNCE),
    SOFT_BOUNCED("Bounce/Transient", MessageStatus.FAILED, FailureReason.DELIVERY_FAILED),
    REJECTED("Reject", MessageStatus.FAILED, FailureReason.REJECTED),
    COMPLAINED("Complaint", null, blocks = BlockReason.OPT_OUT),
    ;

    val metricValue: String
        get() = messageStatus?.name ?: name
}
