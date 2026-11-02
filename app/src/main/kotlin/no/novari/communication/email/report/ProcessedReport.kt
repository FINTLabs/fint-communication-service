package no.novari.communication.email.report

import no.novari.communication.message.StoredMessage

data class ProcessedReport(
    val report: DeliveryReport,
    val message: StoredMessage?,
    val changed: Boolean,
    val blocked: Boolean,
)
