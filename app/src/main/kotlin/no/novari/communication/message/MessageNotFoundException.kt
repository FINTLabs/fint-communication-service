package no.novari.communication.message

import no.novari.communication.message.domain.MessageId

class MessageNotFoundException(
    val messageId: MessageId,
) : RuntimeException("Meldingen finnes ikke")
