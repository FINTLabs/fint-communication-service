package no.novari.communication.api

import no.novari.communication.api.validation.SendMessageRequestValidator
import no.novari.communication.message.MessageService
import no.novari.communication.model.MessageAcceptedResponse
import no.novari.communication.model.SendMessageRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(MessageController.MESSAGES_PATH)
class MessageController(
    private val validator: SendMessageRequestValidator,
    private val messageService: MessageService,
) {
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun send(
        @RequestBody request: SendMessageRequest,
    ): MessageAcceptedResponse {
        val validated = validator.validate(request)
        val id = messageService.receive(validated.tenant, validated.toPayload())
        return MessageAcceptedResponse(id.value)
    }

    companion object {
        const val MESSAGES_PATH = "/api/v1/messages"
    }
}
