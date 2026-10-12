package no.novari.communication.email.report

import no.novari.communication.message.domain.MessageId
import java.time.Instant
import java.util.Base64
import java.util.UUID

object DeliveryReportEvents {
    fun cloudEvent(
        messageId: MessageId,
        status: String,
        recipient: String = "mottaker@rogfk.no",
        deliveryAttemptTimeStamp: Instant? = Instant.parse("2026-10-12T08:00:00.1234567Z"),
        type: String = DeliveryReportParser.EVENT_TYPE,
        statusMessage: String = "Status Message",
    ): String =
        """
        {
          "specversion": "1.0",
          "id": "${UUID.randomUUID()}",
          "source": "/subscriptions/0000/resourceGroups/rg/providers/microsoft.communication/communicationservices/acs",
          "subject": "sender/no-reply@novari.no/recipient/$recipient/messageId/${messageId.value}",
          "type": "$type",
          "time": "2026-10-12T08:00:01Z",
          "datacontenttype": "application/json",
          "data": {
            "sender": "no-reply@novari.no",
            "recipient": "$recipient",
            "messageId": "${messageId.value}",
            "status": "$status",
            "deliveryStatusDetails": {
              "statusMessage": "$statusMessage",
              "recipientMailServerHostName": "mx.rogfk.no"
            }${deliveryAttemptTimeStamp?.let { ",\n    \"deliveryAttemptTimeStamp\": \"$it\"" }.orEmpty()}
          }
        }
        """.trimIndent()

    fun base64(event: String): String = Base64.getEncoder().encodeToString(event.toByteArray(Charsets.UTF_8))
}
