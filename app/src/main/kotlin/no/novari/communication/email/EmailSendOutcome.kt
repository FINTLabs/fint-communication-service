package no.novari.communication.email

import java.time.Duration

sealed interface EmailSendOutcome {
    data object Sent : EmailSendOutcome

    data class Retryable(
        val reason: RetryReason,
        val detail: String,
        val retryAfter: Duration? = null,
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

enum class FailureReason(
    val value: String,
) {
    REJECTED("rejected"),
    OPERATION_FAILED("operation-failed"),
    RETRIES_EXHAUSTED("retries-exhausted"),
}
