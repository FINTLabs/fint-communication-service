package no.novari.communication.message

import no.novari.communication.message.domain.MessageChannel
import no.novari.communication.model.Tenant

data class MessageAccepted(
    val tenant: Tenant,
    val channel: MessageChannel,
)
