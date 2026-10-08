package no.novari.communication.recipient

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RecipientHasherTest {
    private val hasher = RecipientHasher("test-nokkel-for-mottaker-hashing".toByteArray())

    @Test
    fun `the same address gives the same hash regardless of case and surrounding whitespace`() {
        val expected = hasher.hash("ola.nordmann@rogfk.no")

        assertThat(hasher.hash("  Ola.Nordmann@ROGFK.no\t")).isEqualTo(expected)
        assertThat(hasher.hash("OLA.NORDMANN@ROGFK.NO")).isEqualTo(expected)
    }

    @Test
    fun `a different key gives a different hash`() {
        val other = RecipientHasher("annen-nokkel-for-mottaker-hashin".toByteArray())

        assertThat(other.hash("ola.nordmann@rogfk.no")).isNotEqualTo(hasher.hash("ola.nordmann@rogfk.no"))
    }

    @Test
    fun `different addresses give different hashes`() {
        assertThat(hasher.hash("kari@rogfk.no")).isNotEqualTo(hasher.hash("ola@rogfk.no"))
    }

    @Test
    fun `the hash is hex encoded HMAC-SHA256 and does not contain the address`() {
        val hash = hasher.hash("ola.nordmann@rogfk.no")

        assertThat(hash.value).matches("[0-9a-f]{64}")
        assertThat(hash.value).doesNotContain("ola", "nordmann", "rogfk")
    }

    @Test
    fun `toString masks the hash and the key`() {
        val hash = hasher.hash("ola.nordmann@rogfk.no")

        assertThat(hash.toString()).doesNotContain(hash.value)
        assertThat(hasher.toString()).doesNotContain("test-nokkel")
    }
}
