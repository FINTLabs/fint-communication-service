package no.novari.communication.message

import no.novari.communication.message.domain.MessageChannel
import no.novari.communication.message.domain.MessageId
import no.novari.communication.model.FailureReason
import no.novari.communication.model.MessageStatus
import no.novari.communication.model.Tenant
import java.time.Instant

data class StoredMessage(
    val id: MessageId,
    val tenant: Tenant,
    val channel: MessageChannel,
    val templateId: String,
    val status: MessageStatus,
    val failureReason: FailureReason?,
    val receivedAt: Instant,
    val updatedAt: Instant,
)
