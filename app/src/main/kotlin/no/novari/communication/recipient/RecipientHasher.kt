package no.novari.communication.recipient

import java.util.Base64
import java.util.HexFormat
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class RecipientHasher(
    key: ByteArray,
) {
    private val keySpec = SecretKeySpec(key.copyOf(), ALGORITHM)

    fun hash(address: String): RecipientHash {
        val normalized = address.trim().lowercase(Locale.ROOT)
        val mac = Mac.getInstance(ALGORITHM).apply { init(keySpec) }
        return RecipientHash(HexFormat.of().formatHex(mac.doFinal(normalized.toByteArray(Charsets.UTF_8))))
    }

    override fun toString(): String = "RecipientHasher(***)"

    companion object {
        const val ALGORITHM = "HmacSHA256"
        const val MIN_KEY_BYTES = 32
        const val KEY_ENVIRONMENT_VARIABLE = "COMMUNICATION_RECIPIENT_HASHING_KEY"
        private const val KEY_DESCRIPTION =
            "Nøkkelen for mottaker-hashing (communication.recipient-hashing.key / $KEY_ENVIRONMENT_VARIABLE)"

        fun fromBase64Key(key: String?): RecipientHasher {
            check(!key.isNullOrBlank()) { "$KEY_DESCRIPTION mangler" }
            val bytes =
                try {
                    Base64.getDecoder().decode(key.trim())
                } catch (_: IllegalArgumentException) {
                    throw IllegalStateException("$KEY_DESCRIPTION er ikke gyldig base64")
                }
            check(bytes.size >= MIN_KEY_BYTES) {
                "$KEY_DESCRIPTION må være minst $MIN_KEY_BYTES bytes etter base64-dekoding"
            }
            return RecipientHasher(bytes)
        }
    }
}
