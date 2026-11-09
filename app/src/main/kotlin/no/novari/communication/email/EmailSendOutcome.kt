package no.novari.communication.email

import no.novari.communication.model.FailureReason

sealed interface EmailSendOutcome {
    data object Sent : EmailSendOutcome

    data class Retryable(
        val reason: RetryReason,
        val detail: String,
    ) : EmailSendOutcome

    data class Permanent(
        val reason: FailureReason,
        val detail: String,
    ) : EmailSendOutcome
}

enum class RetryReason(
    val value: String,
) {
    TIMEOUT("timeout"),
    THROTTLED("throttled"),
    SERVER_ERROR("server-error"),
    UNAUTHORIZED("unauthorized"),
    IO("io"),
    UNEXPECTED("unexpected"),
}

val FailureReason.value: String
    get() = name.lowercase().replace('_', '-')
