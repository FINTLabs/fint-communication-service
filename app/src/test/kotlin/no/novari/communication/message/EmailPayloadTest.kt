package no.novari.communication.message

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class EmailPayloadTest {
    @Test
    fun `the channel is email`() {
        assertThat(validPayload().channel).isEqualTo(MessageChannel.EMAIL)
    }

    @Test
    fun `replyTo is optional`() {
        assertThat(validPayload().replyTo).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   "])
    fun `a blank recipient is rejected`(to: String) {
        assertThatThrownBy { validPayload().copy(to = to) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("to")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   "])
    fun `a blank subject is rejected`(subject: String) {
        assertThatThrownBy { validPayload().copy(subject = subject) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("subject")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   "])
    fun `a blank body is rejected`(body: String) {
        assertThatThrownBy { validPayload().copy(body = body) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("body")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   "])
    fun `a blank replyTo is rejected when set`(replyTo: String) {
        assertThatThrownBy { validPayload().copy(replyTo = replyTo) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("replyTo")
    }

    @Test
    fun `toString masks every field that may contain personal data`() {
        val payload = validPayload().copy(replyTo = "kari.nordmann@rogfk.no")

        assertThat(payload.toString())
            .isEqualTo("EmailPayload(to=***, subject=***, body=***, replyTo=kari.nordmann@rogfk.no)")
            .doesNotContain("ola.nordmann", "Vedtak", "fødselsnummer")
    }

    @Test
    fun `toString shows that replyTo is absent`() {
        assertThat(validPayload().toString()).endsWith("replyTo=null)")
    }

    @Test
    fun `validation messages do not echo the rejected values`() {
        assertThatThrownBy { validPayload().copy(to = "  ") }
            .hasMessage("Mottaker (to) kan ikke være tom")
    }

    private fun validPayload() =
        EmailPayload(
            to = "ola.nordmann@rogfk.no",
            subject = "Vedtak i saken din",
            body = "Hei Ola, ditt fødselsnummer er registrert.",
        )
}
