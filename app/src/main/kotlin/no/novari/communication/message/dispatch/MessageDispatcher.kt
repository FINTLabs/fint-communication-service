package no.novari.communication.message.dispatch

import no.novari.communication.message.domain.OutgoingMessage

fun interface MessageDispatcher {
    fun dispatch(message: OutgoingMessage)
}
