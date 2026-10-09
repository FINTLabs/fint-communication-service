package no.novari.communication.message.dispatch

import io.github.oshai.kotlinlogging.KotlinLogging
import no.novari.communication.message.domain.OutgoingMessage
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
class QueueingMessageDispatcher(
    private val repository: DispatchQueueRepository,
    private val codec: PayloadCodec,
) : MessageDispatcher {
    private val logger = KotlinLogging.logger {}

    @Transactional(propagation = Propagation.MANDATORY)
    override fun dispatch(message: OutgoingMessage) {
        repository.enqueue(message, codec.encode(message.id, message.payload))
        logger.info {
            "Melding mottatt id=${message.id.value} tenant=${message.tenant} " +
                "channel=${message.channel} templateId=${message.payload.templateId}"
        }
    }
}
