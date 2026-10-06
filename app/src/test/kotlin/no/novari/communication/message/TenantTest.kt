package no.novari.communication.message

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class TenantTest {
    @ParameterizedTest
    @ValueSource(strings = ["rogfk.no", "trondelagfylke.no", "bym.oslo.kommune.no", "vestfold-fylke.no"])
    fun `an orgId with lowercase labels separated by dots is a valid tenant`(orgId: String) {
        assertThat(Tenant(orgId).orgId).isEqualTo(orgId)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            " ",
            "rogfk",
            "Rogfk.no",
            "rogfk.no ",
            "rogfk..no",
            ".rogfk.no",
            "rogfk.no.",
            "-rogfk.no",
            "rog_fk.no",
        ],
    )
    fun `an orgId that is not in orgId format is rejected with a message naming the value`(orgId: String) {
        assertThatThrownBy { Tenant(orgId) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Ugyldig tenant '$orgId'")
    }
}
