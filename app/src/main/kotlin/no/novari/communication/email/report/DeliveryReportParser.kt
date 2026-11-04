package no.novari.communication.email.report

import no.novari.communication.email.SesEmailAdapter
import no.novari.communication.message.domain.MessageId
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

class DeliveryReportParser(
    private val jsonMapper: JsonMapper,
) {
    fun parse(body: ByteArray): ParsedReport {
        val event = readEvent(body) ?: return ParsedReport.Invalid
        val eventType = event.text("eventType") ?: return ParsedReport.Invalid
        val mail = event.path("mail")
        val messageId =
            mail
                .path("tags")
                .path(SesEmailAdapter.MESSAGE_ID_TAG)
                .firstOrNull()
                ?.takeIf { it.isString }
                ?.asString()
                ?.let(::uuidOrNull) ?: return ParsedReport.Invalid
        val (deliveryEvent, details) =
            when (eventType) {
                "Delivery" -> SesDeliveryEvent.DELIVERED to event.path("delivery")
                "Bounce" -> bounce(event.path("bounce")) ?: return ParsedReport.Invalid
                "Complaint" -> complaint(event.path("complaint"))
                "Reject" -> SesDeliveryEvent.REJECTED to event.path("reject")
                else -> return ParsedReport.Unsupported
            }
        val occurredAt =
            (details.text("timestamp") ?: mail.text("timestamp"))?.let(::instantOrNull)
                ?: return ParsedReport.Invalid
        return ParsedReport.Report(DeliveryReport(MessageId(messageId), deliveryEvent, occurredAt))
    }

    private fun bounce(bounce: JsonNode): Pair<SesDeliveryEvent, JsonNode>? {
        val event =
            when (bounce.text("bounceType")) {
                "Permanent" -> {
                    if (bounce.text("bounceSubType") in SUPPRESSED_SUBTYPES) {
                        SesDeliveryEvent.SUPPRESSED
                    } else {
                        SesDeliveryEvent.BOUNCED
                    }
                }

                "Transient", "Undetermined" -> {
                    SesDeliveryEvent.SOFT_BOUNCED
                }

                else -> {
                    return null
                }
            }
        return event to bounce
    }

    private fun complaint(complaint: JsonNode): Pair<SesDeliveryEvent, JsonNode> =
        if (complaint.text("complaintSubType") == ON_ACCOUNT_SUPPRESSION_LIST) {
            SesDeliveryEvent.COMPLAINT_SUPPRESSED to complaint
        } else {
            SesDeliveryEvent.COMPLAINED to complaint
        }

    private fun readEvent(body: ByteArray): JsonNode? {
        val node = readTree(body.toString(Charsets.UTF_8)) ?: return null
        if (!node.isObject) return null
        if (node.text("Type") != SNS_NOTIFICATION) return node
        return node.text("Message")?.let(::readTree)?.takeIf { it.isObject }
    }

    private fun readTree(json: String): JsonNode? =
        try {
            jsonMapper.readTree(json)
        } catch (_: JacksonException) {
            null
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

    private companion object {
        const val SNS_NOTIFICATION = "Notification"
        const val ON_ACCOUNT_SUPPRESSION_LIST = "OnAccountSuppressionList"
        val SUPPRESSED_SUBTYPES = setOf("Suppressed", ON_ACCOUNT_SUPPRESSION_LIST)
    }
}
