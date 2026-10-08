package no.novari.communication.message.domain

import no.novari.communication.model.Tenant
import java.time.Clock
import java.time.Instant

data class OutgoingMessage(
    val id: MessageId,
    val tenant: Tenant,
    val payload: MessagePayload,
    val status: MessageStatus,
    val receivedAt: Instant,
) {
    val channel: MessageChannel
        get() = payload.channel

    companion object {
        fun receive(
            tenant: Tenant,
            payload: MessagePayload,
            clock: Clock = Clock.systemUTC(),
        ): OutgoingMessage =
            OutgoingMessage(
                id = MessageId.generate(),
                tenant = tenant,
                payload = payload,
                status = MessageStatus.RECEIVED,
                receivedAt = clock.instant(),
            )
    }
}
