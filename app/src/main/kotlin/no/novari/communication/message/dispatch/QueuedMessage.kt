package no.novari.communication.message.dispatch

import no.novari.communication.message.domain.MessageChannel
import no.novari.communication.message.domain.MessageId
import no.novari.communication.model.Tenant
import java.time.Instant

class QueuedMessage(
    val id: MessageId,
    val tenant: Tenant,
    val channel: MessageChannel,
    val templateId: String,
    val attempts: Int,
    val receivedAt: Instant,
    val payload: ByteArray,
) {
    override fun toString(): String =
        "QueuedMessage(id=${id.value}, tenant=$tenant, channel=$channel, templateId=$templateId, attempts=$attempts)"
}
