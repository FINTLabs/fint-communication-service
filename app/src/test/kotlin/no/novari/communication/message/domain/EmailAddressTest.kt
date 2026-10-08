package no.novari.communication.message.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class EmailAddressTest {
    @ParameterizedTest
    @ValueSource(
        strings = ["ola@rogfk.no", "ola.nordmann+varsel@post.rogfk.no", "no-reply@novari.no", "O_L.A@ROGFK.NO"],
    )
    fun `a single address with a dotted domain is valid`(address: String) {
        assertThat(EmailAddress.isValid(address)).isTrue()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "ola",
            "ola@rogfk",
            "@rogfk.no",
            "ola@",
            "ola @rogfk.no",
            "ola@rogfk.no ",
            "Ola <ola@rogfk.no>",
            "ola@rogfk.no,kari@rogfk.no",
            "ola@rogfk..no",
            "ola@-rogfk.no",
            "øla@rogfk.no",
        ],
    )
    fun `anything else is invalid`(address: String) {
        assertThat(EmailAddress.isValid(address)).isFalse()
    }

    @Test
    fun `an address longer than 254 characters is invalid`() {
        val address = "a".repeat(64) + "@" + "b".repeat(187) + ".no"

        assertThat(address).hasSize(EmailAddress.MAX_LENGTH + 1)
        assertThat(EmailAddress.isValid(address)).isFalse()
    }
}
