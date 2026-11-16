package no.novari.communication.api

import no.novari.communication.api.validation.SendMessageRequestValidator
import no.novari.communication.message.MessageService
import no.novari.communication.message.domain.MessageId
import no.novari.communication.model.MessageAcceptedResponse
import no.novari.communication.model.MessageStatusResponse
import no.novari.communication.model.SendMessageRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

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

    @GetMapping("/{id}")
    fun status(
        @PathVariable id: UUID,
    ): MessageStatusResponse {
        val message = messageService.find(MessageId(id))
        return MessageStatusResponse(
            id = message.id.value,
            status = message.status,
            failureReason = message.failureReason,
            receivedAt = message.receivedAt,
            updatedAt = message.updatedAt,
        )
    }

    companion object {
        const val MESSAGES_PATH = "/api/v1/messages"
    }
}
