package no.novari.communication.message.dispatch

import no.novari.communication.TEST_DISPATCH_ENCRYPTION_KEY
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import javax.crypto.AEADBadTagException

class PayloadCipherTest {
    private val cipher = PayloadCipher.fromBase64Key(TEST_DISPATCH_ENCRYPTION_KEY)
    private val plaintext = "ola.nordmann@rogfk.no".toByteArray()
    private val associatedData = byteArrayOf(1, 2, 3)

    @Test
    fun `decrypts what it encrypted`() {
        assertThat(cipher.decrypt(cipher.encrypt(plaintext, associatedData), associatedData)).isEqualTo(plaintext)
    }

    @Test
    fun `the ciphertext neither contains the plaintext nor repeats itself`() {
        val first = cipher.encrypt(plaintext, associatedData)
        val second = cipher.encrypt(plaintext, associatedData)

        assertThat(String(first, Charsets.ISO_8859_1)).doesNotContain("ola.nordmann")
        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `a modified ciphertext is rejected`() {
        val encrypted = cipher.encrypt(plaintext, associatedData)
        encrypted[encrypted.lastIndex] = (encrypted.last() + 1).toByte()

        assertThatThrownBy { cipher.decrypt(encrypted, associatedData) }.isInstanceOf(AEADBadTagException::class.java)
    }

    @Test
    fun `a ciphertext for another message is rejected`() {
        val encrypted = cipher.encrypt(plaintext, associatedData)

        assertThatThrownBy { cipher.decrypt(encrypted, byteArrayOf(9)) }.isInstanceOf(AEADBadTagException::class.java)
    }

    @Test
    fun `a missing key fails with the variable name`() {
        assertThatThrownBy { PayloadCipher.fromBase64Key(null) }
            .hasMessageContaining("COMMUNICATION_DISPATCH_ENCRYPTION_KEY")
            .hasMessageContaining("mangler")
    }

    @Test
    fun `a key of the wrong length fails without revealing it`() {
        val shortKey = "a29ydC1ub2trZWw="

        assertThatThrownBy { PayloadCipher.fromBase64Key(shortKey) }
            .hasMessageContaining("32 bytes")
            .message()
            .doesNotContain(shortKey)
    }

    @Test
    fun `an invalid base64 key fails without revealing it`() {
        val invalidKey = "ikke-base64-men-hemmelig!!"

        assertThatThrownBy { PayloadCipher.fromBase64Key(invalidKey) }
            .hasMessageContaining("base64")
            .message()
            .doesNotContain(invalidKey)
    }

    @Test
    fun `toString does not expose the key`() {
        assertThat(cipher.toString()).doesNotContain(TEST_DISPATCH_ENCRYPTION_KEY)
    }
}
