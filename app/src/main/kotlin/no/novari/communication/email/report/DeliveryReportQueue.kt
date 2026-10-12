package no.novari.communication.email.report

import java.time.Duration

interface DeliveryReportQueue {
    fun receive(
        maxMessages: Int,
        visibilityTimeout: Duration,
    ): List<QueuedReport>

    fun delete(report: QueuedReport)

    fun approximateSize(): Long
}
