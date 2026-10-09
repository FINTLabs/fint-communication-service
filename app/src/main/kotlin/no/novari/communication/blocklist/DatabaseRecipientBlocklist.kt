package no.novari.communication.blocklist

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.novari.communication.message.domain.OutgoingMessage
import no.novari.communication.recipient.RecipientHash
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
class DatabaseRecipientBlocklist(
    private val repository: BlocklistRepository,
    private val meterRegistry: MeterRegistry,
) : RecipientBlocklist {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun checkNotBlocked(
        message: OutgoingMessage,
        recipient: RecipientHash,
    ) {
        if (repository.isBlocked(recipient, message.receivedAt)) {
            Counter
                .builder(REJECTED_METRIC)
                .tag("tenant", message.tenant.name)
                .register(meterRegistry)
                .increment()
            throw RecipientBlockedException(message.tenant, message.id)
        }
    }

    companion object {
        const val REJECTED_METRIC = "communication.blocklist.rejected"
    }
}
