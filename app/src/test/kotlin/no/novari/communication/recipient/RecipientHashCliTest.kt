package no.novari.communication.recipient

import no.novari.communication.TEST_RECIPIENT_HASHING_KEY
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class RecipientHashCliTest {
    private val hasher = RecipientHasher.fromBase64Key(TEST_RECIPIENT_HASHING_KEY)
    private val output = ByteArrayOutputStream()
    private val error = ByteArrayOutputStream()

    @Test
    fun `prints the same hash as the service for each address and skips blank lines`() {
        val exitCode = run(mapOf(KEY to TEST_RECIPIENT_HASHING_KEY), "  Ola@RogFK.no \n\n   \nkari@rogfk.no\n")

        assertThat(exitCode).isZero()
        assertThat(output.toString().lines().filter { it.isNotEmpty() })
            .containsExactly(hasher.hash("ola@rogfk.no").value, hasher.hash("kari@rogfk.no").value)
        assertThat(error.toString()).isEmpty()
    }

    @Test
    fun `never prints the address or the key`() {
        run(mapOf(KEY to TEST_RECIPIENT_HASHING_KEY), "ola@rogfk.no\n")

        assertThat(output.toString() + error.toString())
            .doesNotContainIgnoringCase("ola@rogfk.no")
            .doesNotContain(TEST_RECIPIENT_HASHING_KEY)
    }

    @Test
    fun `fails with the variable name when the key is missing`() {
        val exitCode = run(emptyMap(), "ola@rogfk.no\n")

        assertThat(exitCode).isEqualTo(1)
        assertThat(error.toString()).contains(KEY).contains("mangler")
        assertThat(output.toString()).isEmpty()
    }

    @Test
    fun `fails without revealing an invalid key`() {
        val shortKey = "a29ydC1ub2trZWw="

        val exitCode = run(mapOf(KEY to shortKey), "ola@rogfk.no\n")

        assertThat(exitCode).isEqualTo(1)
        assertThat(error.toString()).contains(KEY).contains("minst 32 bytes").doesNotContain(shortKey)
        assertThat(output.toString()).isEmpty()
    }

    private fun run(
        environment: Map<String, String>,
        input: String,
    ): Int =
        RecipientHashCli.run(
            environment,
            input.reader().buffered(),
            PrintStream(output, true, Charsets.UTF_8),
            PrintStream(error, true, Charsets.UTF_8),
        )

    private companion object {
        const val KEY = "COMMUNICATION_RECIPIENT_HASHING_KEY"
    }
}
