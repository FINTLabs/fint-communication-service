package no.novari.communication.email

import no.novari.communication.message.domain.EmailPayload
import no.novari.communication.message.domain.MessageId

fun interface EmailAdapter {
    fun send(
        id: MessageId,
        email: EmailPayload,
    ): EmailSendOutcome
}
