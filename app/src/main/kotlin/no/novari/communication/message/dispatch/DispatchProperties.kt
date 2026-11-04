package no.novari.communication.message.dispatch

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("communication.dispatch")
class DispatchProperties(
    val batchSize: Int,
    val lease: Duration,
    val maxAttempts: Int,
    val initialBackoff: Duration,
    val maxBackoff: Duration,
    val encryptionKey: String?,
) {
    init {
        require(batchSize > 0) { "communication.dispatch.batch-size må være større enn 0" }
        require(maxAttempts > 0) { "communication.dispatch.max-attempts må være større enn 0" }
        require(!initialBackoff.isNegative && initialBackoff <= maxBackoff) {
            "communication.dispatch.initial-backoff må være mellom 0 og max-backoff"
        }
    }

    fun backoffAfter(attempts: Int): Duration {
        val doublings = (attempts - 1).coerceIn(0, MAX_DOUBLINGS)
        return initialBackoff.multipliedBy(1L shl doublings).coerceAtMost(maxBackoff)
    }

    override fun toString(): String =
        "DispatchProperties(batchSize=$batchSize, lease=$lease, " +
            "maxAttempts=$maxAttempts, initialBackoff=$initialBackoff, maxBackoff=$maxBackoff, encryptionKey=***)"

    private companion object {
        const val MAX_DOUBLINGS = 30
    }
}
