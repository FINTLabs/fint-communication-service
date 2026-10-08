package no.novari.communication.limit

import no.novari.communication.message.domain.OutgoingMessage
import no.novari.communication.recipient.RecipientHash

fun interface SendLimiter {
    fun checkAndRecord(
        message: OutgoingMessage,
        recipient: RecipientHash,
    )
}
