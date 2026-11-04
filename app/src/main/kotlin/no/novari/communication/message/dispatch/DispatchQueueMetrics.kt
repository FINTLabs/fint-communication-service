package no.novari.communication.message.dispatch

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.dao.DataAccessException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

@Component
class DispatchQueueMetrics(
    private val repository: DispatchQueueRepository,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val log = KotlinLogging.logger {}
    private val size = AtomicReference(0.0)
    private val oldest = AtomicReference(0.0)

    init {
        Gauge
            .builder(SIZE_METRIC) { size.get() }
            .description("Meldinger i køen som ikke er sendt eller feilet ennå")
            .register(meterRegistry)
        Gauge
            .builder(OLDEST_METRIC) { oldest.get() }
            .description("Alderen på den eldste meldingen i køen")
            .baseUnit("seconds")
            .register(meterRegistry)
    }

    @Scheduled(fixedDelayString = "\${communication.dispatch.queue-metrics-refresh}")
    fun refresh() {
        val stats =
            try {
                repository.stats()
            } catch (exception: DataAccessException) {
                log.warn { "Kunne ikke oppdatere kømetrikkene: ${exception.javaClass.simpleName}" }
                return
            }
        size.set(stats.size.toDouble())
        oldest.set(
            stats.oldestReceivedAt
                ?.let { Duration.between(it, clock.instant()).toMillis() / MILLIS_PER_SECOND }
                ?.coerceAtLeast(0.0) ?: 0.0,
        )
    }

    companion object {
        const val SIZE_METRIC = "communication.dispatch.queue.size"
        const val OLDEST_METRIC = "communication.dispatch.queue.oldest"
        private const val MILLIS_PER_SECOND = 1000.0
    }
}
