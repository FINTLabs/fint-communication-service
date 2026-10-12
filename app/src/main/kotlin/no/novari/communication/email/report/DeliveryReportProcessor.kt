package no.novari.communication.email.report

import no.novari.communication.blocklist.BlockSource
import no.novari.communication.blocklist.BlocklistRepository
import no.novari.communication.message.MessageStore
import no.novari.communication.message.ReportOutcome
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock
import java.time.Duration

class DeliveryReportProcessor(
    private val messageStore: MessageStore,
    private val blocklist: BlocklistRepository,
    private val transactions: TransactionOperations,
    private val blockFor: Duration,
    private val clock: Clock,
) {
    fun process(report: DeliveryReport): ProcessedReport =
        checkNotNull(
            transactions.execute {
                val outcome = applyStatus(report)
                val recipient = report.recipient?.takeIf { report.status.blocksRecipient }
                recipient?.let { blocklist.blockHardBounce(it, BlockSource.ACS, report.occurredAt + blockFor) }
                ProcessedReport(
                    report = report,
                    message =
                        when (outcome) {
                            is ReportOutcome.Applied -> outcome.message
                            is ReportOutcome.Unchanged -> outcome.message
                            ReportOutcome.NotFound -> null
                        },
                    changed = outcome is ReportOutcome.Applied,
                    blocked = recipient != null,
                )
            },
        )

    private fun applyStatus(report: DeliveryReport): ReportOutcome {
        val status =
            report.status.messageStatus
                ?: return messageStore.find(report.messageId)?.let(ReportOutcome::Unchanged) ?: ReportOutcome.NotFound
        return messageStore.applyReport(report.messageId, status, report.status.failureReason, clock.instant())
    }
}
