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
    fun `fails to start with an unknown provider`() {
        contextRunner.withPropertyValues("communication.email.provider=ukjent").run { context ->
            assertThat(context).hasFailed()
        }
    }
}
