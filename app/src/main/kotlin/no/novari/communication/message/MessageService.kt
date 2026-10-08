package no.novari.communication.message

import no.novari.communication.message.dispatch.MessageDispatcher
import no.novari.communication.message.domain.MessageId
import no.novari.communication.message.domain.MessagePayload
import no.novari.communication.message.domain.OutgoingMessage
import no.novari.communication.model.Tenant
import org.springframework.stereotype.Service
import java.time.Clock

@Service
class MessageService(
    private val dispatcher: MessageDispatcher,
    private val clock: Clock,
) {
    fun receive(
        tenant: Tenant,
        payload: MessagePayload,
    ): MessageId {
        val message = OutgoingMessage.receive(tenant, payload, clock)
        dispatcher.dispatch(message)
        return message.id
    }
}
