package no.novari.communication.message

import no.novari.communication.message.domain.MessageId
import no.novari.communication.message.domain.OutgoingMessage
import no.novari.communication.model.FailureReason
import no.novari.communication.model.MessageStatus
import java.time.Instant

interface MessageStore {
    fun save(message: OutgoingMessage)

    fun find(id: MessageId): StoredMessage?

    fun complete(
        id: MessageId,
        status: MessageStatus,
        failureReason: FailureReason?,
        at: Instant,
    )
}
