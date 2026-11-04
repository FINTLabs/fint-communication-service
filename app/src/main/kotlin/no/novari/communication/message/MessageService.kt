package no.novari.communication.message

import no.novari.communication.limit.SendLimiter
import no.novari.communication.message.dispatch.MessageDispatcher
import no.novari.communication.message.domain.MessageId
import no.novari.communication.message.domain.MessagePayload
import no.novari.communication.message.domain.OutgoingMessage
import no.novari.communication.model.Tenant
import no.novari.communication.recipient.RecipientHasher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class MessageService(
    private val dispatcher: MessageDispatcher,
    private val recipientHasher: RecipientHasher,
    private val sendLimiter: SendLimiter,
    private val clock: Clock,
) {
    @Transactional
    fun receive(
        tenant: Tenant,
        payload: MessagePayload,
    ): MessageId {
        val message = OutgoingMessage.receive(tenant, payload, clock)
        sendLimiter.checkAndRecord(message, recipientHasher.hash(payload.to))
        dispatcher.dispatch(message)
        return message.id
    }
}
