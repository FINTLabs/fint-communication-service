package no.novari.communication.email.report

sealed interface ParsedReport {
    data class Report(
        val report: DeliveryReport,
    ) : ParsedReport

    data object Unsupported : ParsedReport

    data object Invalid : ParsedReport
}
