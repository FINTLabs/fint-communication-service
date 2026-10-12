package no.novari.communication.email.report

import no.novari.communication.message.domain.MessageId
import no.novari.communication.recipient.RecipientHash
import java.time.Instant

data class DeliveryReport(
    val messageId: MessageId,
    val status: AcsDeliveryStatus,
    val recipient: RecipientHash?,
    val occurredAt: Instant,
)
