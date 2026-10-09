package no.novari.communication

import no.novari.communication.api.TestTemplates
import no.novari.communication.message.dispatch.LoggingMessageDispatcher
import no.novari.communication.message.dispatch.MessageDispatcher
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

@TestConfiguration(proxyBeanMethods = false)
class IntegrationTestConfiguration {
    @Bean
    @Primary
    fun mutableClock(): MutableClock = MutableClock(Instant.now())

    @Bean
    @Primary
    fun controllableDispatcher(): ControllableDispatcher = ControllableDispatcher()

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

class ControllableDispatcher : MessageDispatcher {
    private val delegate = LoggingMessageDispatcher()

    @Volatile
    var failure: RuntimeException? = null

    override fun dispatch(message: OutgoingMessage) {
        failure?.let { throw it }
        delegate.dispatch(message)
    }
}
