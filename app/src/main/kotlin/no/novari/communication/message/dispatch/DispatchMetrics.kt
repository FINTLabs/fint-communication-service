package no.novari.communication.message.dispatch

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.novari.communication.email.RetryReason
import no.novari.communication.email.value
import no.novari.communication.model.FailureReason
import org.springframework.stereotype.Component

@Component
class DispatchMetrics(
    private val meterRegistry: MeterRegistry,
) {
    fun sent(message: QueuedMessage) {
        counter(SENT_METRIC, message).register(meterRegistry).increment()
    }

    fun retried(
        message: QueuedMessage,
        reason: RetryReason,
    ) {
        counter(RETRIED_METRIC, message).tag("reason", reason.value).register(meterRegistry).increment()
    }

    fun failed(
        message: QueuedMessage,
        reason: FailureReason,
    ) {
        counter(FAILED_METRIC, message).tag("reason", reason.value).register(meterRegistry).increment()
    }

    private fun counter(
        name: String,
        message: QueuedMessage,
    ): Counter.Builder =
        Counter
            .builder(name)
            .tag("tenant", message.tenant.name)
            .tag("channel", message.channel.name)

    companion object {
        const val SENT_METRIC = "communication.message.sent"
        const val RETRIED_METRIC = "communication.message.retried"
        const val FAILED_METRIC = "communication.message.failed"
    }
}
