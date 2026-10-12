package no.novari.communication.email.report

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled

class DeliveryReportPoller(
    private val queue: DeliveryReportQueue,
    private val parser: DeliveryReportParser,
    private val processor: DeliveryReportProcessor,
    private val metrics: DeliveryReportMetrics,
    private val properties: DeliveryReportProperties,
) {
    private val log = KotlinLogging.logger {}

    @Scheduled(fixedDelayString = "\${communication.email.delivery-reports.poll-interval}")
    fun poll() {
        val reports =
            try {
                queue.receive(properties.batchSize, properties.visibilityTimeout)
            } catch (exception: RuntimeException) {
                metrics.pollFailed()
                log.warn { "Kunne ikke hente leveringsrapporter fra køen: ${exception.javaClass.simpleName}" }
                return
            }
        reports.forEach(::handle)
    }

    private fun handle(queued: QueuedReport) {
        if (queued.dequeueCount > properties.maxDequeueCount) return discard(queued, DiscardReason.POISON)
        when (val parsed = parser.parse(queued.body)) {
            ParsedReport.Invalid -> discard(queued, DiscardReason.INVALID)
            ParsedReport.Unsupported -> discard(queued, DiscardReason.UNSUPPORTED_TYPE)
            is ParsedReport.Report -> process(queued, parsed.report)
        }
    }

    private fun process(
        queued: QueuedReport,
        report: DeliveryReport,
    ) {
        val processed =
            try {
                processor.process(report)
            } catch (exception: RuntimeException) {
                log.warn {
                    "Kunne ikke lagre leveringsrapporten id=${report.messageId.value} ${describe(queued)}; " +
                        "den behandles på nytt senere: ${exception.javaClass.simpleName}"
                }
                return
            }
        log(processed)
        processed.message?.let { metrics.received(it, report.status) }
            ?: metrics.discarded(DiscardReason.UNKNOWN_MESSAGE)
        delete(queued)
    }

    private fun log(processed: ProcessedReport) {
        val report = processed.report
        val message = processed.message
        if (message == null) {
            log.warn {
                "Leveringsrapport for ukjent melding id=${report.messageId.value} acsStatus=${report.status.value} " +
                    "blokkert=${processed.blocked.yesNo()}"
            }
            return
        }
        log.info {
            "Leveringsrapport id=${message.id.value} acsStatus=${report.status.value} status=${message.status} " +
                "tenant=${message.tenant} channel=${message.channel} templateId=${message.templateId} " +
                "endret=${processed.changed.yesNo()} blokkert=${processed.blocked.yesNo()}"
        }
    }

    private fun discard(
        queued: QueuedReport,
        reason: DiscardReason,
    ) {
        log.warn { "Leveringsrapport forkastet årsak=${reason.value} ${describe(queued)}" }
        metrics.discarded(reason)
        delete(queued)
    }

    private fun delete(queued: QueuedReport) {
        try {
            queue.delete(queued)
        } catch (exception: RuntimeException) {
            metrics.pollFailed()
            log.warn {
                "Kunne ikke slette leveringsrapporten fra køen ${describe(queued)}; den behandles på nytt senere: " +
                    exception.javaClass.simpleName
            }
        }
    }

    private fun describe(queued: QueuedReport): String =
        "kømelding=${queued.queueMessageId} forsøk=${queued.dequeueCount}"

    private fun Boolean.yesNo(): String = if (this) "ja" else "nei"
}
