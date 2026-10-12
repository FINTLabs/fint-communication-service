package no.novari.communication.limit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class LimitConfigurationTest {
    private val contextRunner = ApplicationContextRunner().withUserConfiguration(LimitConfiguration::class.java)

    @Test
    fun `binds the recipient limits`() {
        contextRunner
            .withPropertyValues(
                "communication.limits.recipient.per-hour=10",
                "communication.limits.recipient.per-day=40",
            ).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(LimitProperties::class.java).recipient).isEqualTo(WindowLimits(10, 40))
            }
    }

    @Test
    fun `fails to start without limits`() {
        contextRunner.run { context -> assertThat(context).hasFailed() }
    }

    @Test
    fun `fails to start when a limit is not positive`() {
        contextRunner
            .withPropertyValues(
                "communication.limits.recipient.per-hour=0",
                "communication.limits.recipient.per-day=40",
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(rootCauseMessage(context.startupFailure)).contains("per-hour")
            }
    }

    @Test
    fun `fails to start when the daily limit is below the hourly limit`() {
        contextRunner
            .withPropertyValues(
                "communication.limits.recipient.per-hour=10",
                "communication.limits.recipient.per-day=5",
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(rootCauseMessage(context.startupFailure)).contains("per-day")
            }
    }

    private fun rootCauseMessage(failure: Throwable?): String =
        generateSequence(failure) { it.cause }.last().message.orEmpty()
}
