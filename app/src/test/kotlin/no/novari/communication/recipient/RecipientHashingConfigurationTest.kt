package no.novari.communication.recipient

import no.novari.communication.TEST_RECIPIENT_HASHING_KEY
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class RecipientHashingConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner().withUserConfiguration(RecipientHashingConfiguration::class.java)

    @Test
    fun `starts with a valid key`() {
        contextRunner
            .withPropertyValues("communication.recipient-hashing.key=$TEST_RECIPIENT_HASHING_KEY")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(RecipientHasher::class.java)
            }
    }

    @Test
    fun `fails to start when the key is missing`() {
        contextRunner.run { context ->
            assertThat(context).hasFailed()
            assertThat(rootCauseMessage(context.startupFailure))
                .contains("COMMUNICATION_RECIPIENT_HASHING_KEY")
                .contains("mangler")
        }
    }

    @Test
    fun `fails to start when the key is not base64 without revealing it`() {
        val invalidKey = "ikke-base64-men-hemmelig!!"

        contextRunner
            .withPropertyValues("communication.recipient-hashing.key=$invalidKey")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(rootCauseMessage(context.startupFailure))
                    .contains("COMMUNICATION_RECIPIENT_HASHING_KEY")
                    .doesNotContain(invalidKey)
            }
    }

    @Test
    fun `fails to start when the key is too short without revealing it`() {
        val shortKey = "a29ydC1ub2trZWw="

        contextRunner
            .withPropertyValues("communication.recipient-hashing.key=$shortKey")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(rootCauseMessage(context.startupFailure))
                    .contains("minst 32 bytes")
                    .doesNotContain(shortKey)
            }
    }

    private fun rootCauseMessage(failure: Throwable?): String =
        generateSequence(failure) { it.cause }.last().message.orEmpty()
}
