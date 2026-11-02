package no.novari.communication.message

sealed interface ReportOutcome {
    data class Applied(
        val message: StoredMessage,
    ) : ReportOutcome

    data class Unchanged(
        val message: StoredMessage,
    ) : ReportOutcome

    data object NotFound : ReportOutcome
}
