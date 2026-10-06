package no.novari.communication.client

import no.novari.communication.model.MessageAcceptedResponse
import no.novari.communication.model.SendMessageRequest
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange

@HttpExchange(MESSAGES_PATH)
interface CommunicationClient {
    @PostExchange
    fun send(
        @RequestBody request: SendMessageRequest,
    ): MessageAcceptedResponse
}
