package no.novari.communication

import no.novari.communication.api.TestTemplates
import no.novari.communication.email.EmailAdapter
import no.novari.communication.email.EmailSendOutcome
import no.novari.communication.message.dispatch.MessageDispatcher
import no.novari.communication.message.dispatch.QueueingMessageDispatcher
import no.novari.communication.message.domain.EmailPayload
import no.novari.communication.message.domain.MessageId
import no.novari.communication.message.domain.OutgoingMessage
import no.novari.communication.template.EmailTemplateCatalog
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

@TestConfiguration(proxyBeanMethods = false)
class IntegrationTestConfiguration {
    @Bean
    @Primary
    fun mutableClock(): MutableClock = MutableClock(Instant.now())

    @Bean
    @Primary
    fun controllableDispatcher(queueingDispatcher: QueueingMessageDispatcher): ControllableDispatcher =
        ControllableDispatcher(queueingDispatcher)

    @Bean
    @Primary
    fun controllableEmailAdapter(): ControllableEmailAdapter = ControllableEmailAdapter()

    @Bean
    @Primary
    fun testEmailTemplateCatalog(): EmailTemplateCatalog = TestTemplates.catalog()
}

class MutableClock(
    @Volatile private var now: Instant,
) : Clock() {
    fun set(instant: Instant) {
        now = instant
    }

    fun advance(duration: Duration) {
        now += duration
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}

class ControllableDispatcher(
    private val delegate: MessageDispatcher,
) : MessageDispatcher {
    @Volatile
    var failure: RuntimeException? = null

    override fun dispatch(message: OutgoingMessage) {
        failure?.let { throw it }
        delegate.dispatch(message)
    }
}

class ControllableEmailAdapter : EmailAdapter {
    private val outcomes = ConcurrentLinkedQueue<EmailSendOutcome>()
    val sent = CopyOnWriteArrayList<Pair<MessageId, EmailPayload>>()

    fun respondWith(vararg outcome: EmailSendOutcome) {
        outcomes += outcome
    }

    fun reset() {
        outcomes.clear()
        sent.clear()
    }

    override fun send(
        id: MessageId,
        email: EmailPayload,
    ): EmailSendOutcome {
        sent += id to email
        return outcomes.poll() ?: EmailSendOutcome.Sent
    }
}
