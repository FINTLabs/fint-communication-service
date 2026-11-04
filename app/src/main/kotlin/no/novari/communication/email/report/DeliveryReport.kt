package no.novari.communication.email.report

import no.novari.communication.message.domain.MessageId
import java.time.Instant

data class DeliveryReport(
    val messageId: MessageId,
    val event: SesDeliveryEvent,
    val occurredAt: Instant,
)
