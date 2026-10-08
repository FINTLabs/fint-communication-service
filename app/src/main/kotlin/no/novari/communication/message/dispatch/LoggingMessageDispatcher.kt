package no.novari.communication.message.dispatch

import io.github.oshai.kotlinlogging.KotlinLogging
import no.novari.communication.message.domain.OutgoingMessage
import org.springframework.stereotype.Component

@Component
class LoggingMessageDispatcher : MessageDispatcher {
    private val logger = KotlinLogging.logger {}

    override fun dispatch(message: OutgoingMessage) {
        logger.info {
            "Melding mottatt id=${message.id.value} tenant=${message.tenant} " +
                "channel=${message.channel} templateId=${message.payload.templateId}"
        }
    }
}
