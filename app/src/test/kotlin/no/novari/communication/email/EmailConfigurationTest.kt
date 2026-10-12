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
    fun `uses the ACS adapter when the provider is acs`() {
        contextRunner
            .withPropertyValues(
                "communication.email.provider=acs",
                "communication.email.sender=no-reply@novari.no",
                "communication.email.acs.connection-string=${FakeAcs.CONNECTION_STRING}",
            ).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean<EmailAdapter>()).isInstanceOf(AcsEmailAdapter::class.java)
            }
    }

    @Test
    fun `fails to start with acs and no connection string`() {
        contextRunner
            .withPropertyValues("communication.email.provider=acs", "communication.email.sender=no-reply@novari.no")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(rootCauseMessage(context.startupFailure))
                    .contains("COMMUNICATION_EMAIL_ACS_CONNECTION_STRING")
                    .contains("mangler")
            }
    }

    @Test
    fun `fails to start with acs and no sender`() {
        contextRunner
            .withPropertyValues(
                "communication.email.provider=acs",
                "communication.email.acs.connection-string=${FakeAcs.CONNECTION_STRING}",
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(rootCauseMessage(context.startupFailure))
                    .contains("communication.email.sender")
                    .doesNotContain(FakeAcs.ACCESS_KEY)
            }
    }

    @Test
    fun `fails to start with an invalid connection string without revealing it`() {
        val invalid = "endpoint=ikke-en-url;hemmelig=verdi"

        contextRunner
            .withPropertyValues(
                "communication.email.provider=acs",
                "communication.email.sender=no-reply@novari.no",
                "communication.email.acs.connection-string=$invalid",
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(rootCauseMessage(context.startupFailure))
                    .contains("COMMUNICATION_EMAIL_ACS_CONNECTION_STRING")
                    .doesNotContain("hemmelig")
            }
    }

    @Test
    fun `fails to start with an unknown provider`() {
        contextRunner.withPropertyValues("communication.email.provider=ses").run { context ->
            assertThat(context).hasFailed()
        }
    }

    @Test
    fun `properties do not expose the connection string`() {
        assertThat(AcsProperties(connectionString = FakeAcs.CONNECTION_STRING).toString())
            .doesNotContain(FakeAcs.ACCESS_KEY)
    }

    private fun rootCauseMessage(failure: Throwable?): String =
        generateSequence(failure) { it.cause }.last().message.orEmpty()
}
