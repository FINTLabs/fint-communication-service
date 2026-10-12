package no.novari.communication.email.report

import no.novari.communication.message.domain.MessageId
import no.novari.communication.recipient.RecipientHasher
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

class DeliveryReportParser(
    private val jsonMapper: JsonMapper,
    private val recipientHasher: RecipientHasher,
) {
    fun parse(body: ByteArray): ParsedReport {
        val event = readEvent(body) ?: return ParsedReport.Invalid
        if (event.text("type") != EVENT_TYPE) return ParsedReport.Unsupported
        val data = event.path("data")
        val messageId = data.text("messageId")?.let(::uuidOrNull) ?: return ParsedReport.Invalid
        val status = data.text("status")?.let(AcsDeliveryStatus::fromValue) ?: return ParsedReport.Invalid
        val occurredAt =
            (data.text("deliveryAttemptTimeStamp") ?: event.text("time"))?.let(::instantOrNull)
                ?: return ParsedReport.Invalid
        val recipient = if (status.blocksRecipient) data.text("recipient")?.let(recipientHasher::hash) else null
        return ParsedReport.Report(DeliveryReport(MessageId(messageId), status, recipient, occurredAt))
    }

    private fun readEvent(body: ByteArray): JsonNode? {
        val json = decode(body) ?: return null
        val node =
            try {
                jsonMapper.readTree(json)
            } catch (_: JacksonException) {
                return null
            }
        return when {
            node.isObject -> node
            node.isArray && node.size() == 1 && node[0].isObject -> node[0]
            else -> null
        }
    }

    private fun decode(body: ByteArray): ByteArray? {
        val text = body.toString(Charsets.UTF_8).trim()
        if (text.startsWith("{") || text.startsWith("[")) return text.toByteArray(Charsets.UTF_8)
        return try {
            Base64.getMimeDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun JsonNode.text(field: String): String? =
        path(field).takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }

    private fun uuidOrNull(value: String): UUID? =
        try {
            UUID.fromString(value)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun instantOrNull(value: String): Instant? =
        try {
            OffsetDateTime.parse(value).toInstant()
        } catch (_: DateTimeException) {
            null
        }

    companion object {
        const val EVENT_TYPE = "Microsoft.Communication.EmailDeliveryReportReceived"
    }
}
