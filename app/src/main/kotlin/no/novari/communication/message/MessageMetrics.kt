package no.novari.communication.message

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

@Component
class MessageMetrics(
    private val meterRegistry: MeterRegistry,
) {
    @TransactionalEventListener
    fun onAccepted(event: MessageAccepted) {
        Counter
            .builder(ACCEPTED_METRIC)
            .tag("tenant", event.tenant.name)
            .tag("channel", event.channel.name)
            .register(meterRegistry)
            .increment()
    }

    companion object {
        const val ACCEPTED_METRIC = "communication.message.accepted"
    }
}
