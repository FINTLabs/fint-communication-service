package no.novari.communication.recipient

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
    }
}
