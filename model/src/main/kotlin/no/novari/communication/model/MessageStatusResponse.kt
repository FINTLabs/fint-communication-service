package no.novari.communication.model

import java.time.Instant
import java.util.UUID

data class MessageStatusResponse(
    val id: UUID,
    val status: MessageStatus,
    val failureReason: FailureReason?,
    val receivedAt: Instant,
    val updatedAt: Instant,
)
