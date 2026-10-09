package no.novari.communication.blocklist

import no.novari.communication.message.domain.OutgoingMessage
import no.novari.communication.recipient.RecipientHash

fun interface RecipientBlocklist {
    fun checkNotBlocked(
        message: OutgoingMessage,
        recipient: RecipientHash,
    )
}
