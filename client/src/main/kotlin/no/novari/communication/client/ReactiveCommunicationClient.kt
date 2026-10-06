package no.novari.communication.client

import no.novari.communication.model.MessageAcceptedResponse
import no.novari.communication.model.SendMessageRequest
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange
import reactor.core.publisher.Mono

@HttpExchange(MESSAGES_PATH)
interface ReactiveCommunicationClient {
    @PostExchange
    fun send(
        @RequestBody request: SendMessageRequest,
    ): Mono<MessageAcceptedResponse>
}
