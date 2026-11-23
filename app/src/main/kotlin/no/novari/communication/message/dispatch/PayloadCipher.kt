package no.novari.communication.message.dispatch

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class PayloadCipher(
    key: ByteArray,
) {
    private val keySpec = SecretKeySpec(key.copyOf(), "AES")
    private val random = SecureRandom()

    fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = cipher(Cipher.ENCRYPT_MODE, nonce, associatedData)
        return ByteBuffer
            .allocate(NONCE_BYTES + cipher.getOutputSize(plaintext.size))
            .put(nonce)
            .put(cipher.doFinal(plaintext))
            .array()
    }

    fun decrypt(
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        val nonce = ciphertext.copyOfRange(0, NONCE_BYTES)
        return cipher(Cipher.DECRYPT_MODE, nonce, associatedData).doFinal(
            ciphertext,
            NONCE_BYTES,
            ciphertext.size - NONCE_BYTES,
        )
    }

    private fun cipher(
        mode: Int,
        nonce: ByteArray,
        associatedData: ByteArray,
    ): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(mode, keySpec, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(associatedData)
        }

    override fun toString(): String = "PayloadCipher(***)"

    companion object {
        const val KEY_BYTES = 32
        const val KEY_ENVIRONMENT_VARIABLE = "COMMUNICATION_DISPATCH_ENCRYPTION_KEY"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEY_DESCRIPTION =
            "Nøkkelen for kryptering av meldinger i køen (communication.dispatch.encryption-key / $KEY_ENVIRONMENT_VARIABLE)"

        fun fromBase64Key(key: String?): PayloadCipher {
            check(!key.isNullOrBlank()) { "$KEY_DESCRIPTION mangler" }
            val bytes =
                try {
                    Base64.getDecoder().decode(key.trim())
                } catch (_: IllegalArgumentException) {
                    throw IllegalStateException("$KEY_DESCRIPTION er ikke gyldig base64")
                }
            check(
                bytes.size == KEY_BYTES,
            ) { "$KEY_DESCRIPTION må være nøyaktig $KEY_BYTES bytes etter base64-dekoding" }
            return PayloadCipher(bytes)
        }
    }
}
