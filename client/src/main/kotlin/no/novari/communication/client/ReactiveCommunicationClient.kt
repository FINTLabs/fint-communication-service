package no.novari.communication.client

import no.novari.communication.model.MessageAcceptedResponse
import no.novari.communication.model.MessageStatusResponse
import no.novari.communication.model.SendMessageRequest
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange
import reactor.core.publisher.Mono
import java.util.UUID

@HttpExchange(MESSAGES_PATH)
interface ReactiveCommunicationClient {
    @PostExchange
    fun send(
        @RequestBody request: SendMessageRequest,
    ): Mono<MessageAcceptedResponse>

    @GetExchange("/{id}")
    fun getStatus(
        @PathVariable id: UUID,
    ): Mono<MessageStatusResponse>
}
