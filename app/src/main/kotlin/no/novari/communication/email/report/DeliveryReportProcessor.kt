package no.novari.communication.email.report

import no.novari.communication.blocklist.BlockReason
import no.novari.communication.blocklist.BlockSource
import no.novari.communication.blocklist.BlocklistRepository
import no.novari.communication.message.MessageStore
import no.novari.communication.message.ReportOutcome
import no.novari.communication.recipient.RecipientHash
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
                val blockReason = report.event.blocks
                val recipient = report.recipient?.takeIf { blockReason != null }
                recipient?.let { block(it, blockReason, report) }
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

    private fun block(
        recipient: RecipientHash,
        reason: BlockReason?,
        report: DeliveryReport,
    ) {
        when (reason) {
            BlockReason.HARD_BOUNCE -> {
                blocklist.blockHardBounce(
                    recipient,
                    BlockSource.SES,
                    report.occurredAt + blockFor,
                )
            }

            BlockReason.OPT_OUT -> {
                blocklist.blockOptOut(recipient, BlockSource.SES)
            }

            null -> {
                Unit
            }
        }
    }

    private fun applyStatus(report: DeliveryReport): ReportOutcome {
        val status =
            report.event.messageStatus
                ?: return messageStore.find(report.messageId)?.let(ReportOutcome::Unchanged) ?: ReportOutcome.NotFound
        return messageStore.applyReport(report.messageId, status, report.event.failureReason, clock.instant())
    }
}
