package no.novari.communication.email.report

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.novari.communication.message.StoredMessage

class DeliveryReportMetrics(
    private val meterRegistry: MeterRegistry,
) {
    fun received(
        message: StoredMessage,
        status: AcsDeliveryStatus,
    ) {
        Counter
            .builder(RECEIVED_METRIC)
            .tag("tenant", message.tenant.name)
            .tag("channel", message.channel.name)
            .tag("template", message.templateId)
            .tag("status", status.metricValue)
            .register(meterRegistry)
            .increment()
    }

    fun discarded(reason: DiscardReason) {
        Counter
            .builder(DISCARDED_METRIC)
            .tag("reason", reason.value)
            .register(meterRegistry)
            .increment()
    }

    fun pollFailed() {
        Counter.builder(POLL_FAILED_METRIC).register(meterRegistry).increment()
    }

    companion object {
        const val RECEIVED_METRIC = "communication.delivery_report.received"
        const val DISCARDED_METRIC = "communication.delivery_report.discarded"
        const val POLL_FAILED_METRIC = "communication.delivery_report.poll.failed"
    }
}
