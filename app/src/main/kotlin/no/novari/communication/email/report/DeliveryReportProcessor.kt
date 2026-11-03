package no.novari.communication.email.report

import no.novari.communication.blocklist.BlockReason
import no.novari.communication.blocklist.BlockSource
import no.novari.communication.blocklist.BlocklistRepository
import no.novari.communication.message.MessageStore
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
                report.event.messageStatus?.let {
                    messageStore.applyReport(report.messageId, it, report.event.failureReason, clock.instant())
                }
                val blockReason = report.event.blocks
                val recipient = report.recipient?.takeIf { blockReason != null }
                if (recipient != null && blockReason != null) block(recipient, blockReason, report)
                ProcessedReport(
                    report = report,
                    message = messageStore.find(report.messageId),
                    blocked = recipient != null,
                )
            },
        )

    private fun block(
        recipient: RecipientHash,
        reason: BlockReason,
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
        }
    }
}
