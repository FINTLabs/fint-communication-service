package no.novari.communication.email.report

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.scheduling.annotation.Scheduled
import java.util.concurrent.atomic.AtomicReference

class DeliveryReportQueueMetrics(
    private val queue: DeliveryReportQueue,
    meterRegistry: MeterRegistry,
) {
    private val log = KotlinLogging.logger {}
    private val size = AtomicReference(0.0)

    init {
        Gauge
            .builder(SIZE_METRIC) { size.get() }
            .description("Leveringsrapporter som venter i køen fra Event Grid")
            .register(meterRegistry)
    }

    @Scheduled(fixedDelayString = "\${communication.email.delivery-reports.queue-metrics-refresh}")
    fun refresh() {
        try {
            size.set(queue.approximateSize().toDouble())
        } catch (exception: RuntimeException) {
            log.warn { "Kunne ikke lese lengden på køen for leveringsrapporter: ${exception.javaClass.simpleName}" }
        }
    }

    companion object {
        const val SIZE_METRIC = "communication.delivery_report.queue.size"
    }
}
