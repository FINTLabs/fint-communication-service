package no.novari.communication.email.report

import no.novari.communication.message.MessageStore
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock

class DeliveryReportProcessor(
    private val messageStore: MessageStore,
    private val transactions: TransactionOperations,
    private val clock: Clock,
) {
    fun process(report: DeliveryReport): ProcessedReport =
        checkNotNull(
            transactions.execute {
                report.event.messageStatus?.let {
                    messageStore.applyReport(report.messageId, it, report.event.failureReason, clock.instant())
                }
                ProcessedReport(report = report, message = messageStore.find(report.messageId))
            },
        )
}
