package no.novari.communication.message

import no.novari.communication.blocklist.RecipientBlocklist
import no.novari.communication.limit.SendLimiter
import no.novari.communication.message.dispatch.MessageDispatcher
import no.novari.communication.message.domain.MessageId
import no.novari.communication.message.domain.MessagePayload
import no.novari.communication.message.domain.OutgoingMessage
import no.novari.communication.model.Tenant
import no.novari.communication.recipient.RecipientHasher
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class MessageService(
    private val messageStore: MessageStore,
    private val dispatcher: MessageDispatcher,
    private val recipientHasher: RecipientHasher,
    private val recipientBlocklist: RecipientBlocklist,
    private val sendLimiter: SendLimiter,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @Transactional
    fun receive(
        tenant: Tenant,
        payload: MessagePayload,
    ): MessageId {
        val message = OutgoingMessage.receive(tenant, payload, clock)
        val recipient = recipientHasher.hash(payload.to)
        recipientBlocklist.checkNotBlocked(message, recipient)
        sendLimiter.checkAndRecord(message, recipient)
        messageStore.save(message)
        dispatcher.dispatch(message)
        eventPublisher.publishEvent(MessageAccepted(tenant, payload.channel))
        return message.id
    }

    fun find(id: MessageId): StoredMessage = messageStore.find(id) ?: throw MessageNotFoundException(id)
}
