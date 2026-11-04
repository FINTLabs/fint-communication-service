package no.novari.communication.email

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class EmailConfigurationTest {
    private val contextRunner = ApplicationContextRunner().withUserConfiguration(EmailConfiguration::class.java)

    @Test
    fun `uses the logging adapter by default`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean<EmailAdapter>()).isInstanceOf(LoggingEmailAdapter::class.java)
        }
    }

    @Test
    fun `uses the SES adapter when the provider is ses`() {
        contextRunner
            .withPropertyValues("communication.email.provider=ses", "communication.email.sender=no-reply@novari.no")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean<EmailAdapter>()).isInstanceOf(SesEmailAdapter::class.java)
            }
    }

    @Test
    fun `fails to start with ses and no sender`() {
        contextRunner.withPropertyValues("communication.email.provider=ses").run { context ->
            assertThat(context).hasFailed()
            assertThat(rootCauseMessage(context.startupFailure)).contains("communication.email.sender")
        }
    }

    @Test
    fun `fails to start with ses and an invalid sender`() {
        contextRunner
            .withPropertyValues("communication.email.provider=ses", "communication.email.sender=ikke-en-adresse")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(rootCauseMessage(context.startupFailure)).contains("ikke en gyldig adresse")
            }
    }

    @Test
    fun `fails to start with an unknown provider`() {
        contextRunner.withPropertyValues("communication.email.provider=acs").run { context ->
            assertThat(context).hasFailed()
        }
    }

    private fun rootCauseMessage(failure: Throwable?): String =
        generateSequence(failure) { it.cause }.last().message.orEmpty()
}
