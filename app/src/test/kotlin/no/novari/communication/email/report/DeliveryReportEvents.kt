package no.novari.communication.email.report

import no.novari.communication.message.domain.MessageId
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

object DeliveryReportEvents {
    private val jsonMapper = JsonMapper.builder().build()
    val MAIL_TIMESTAMP: Instant = Instant.parse("2026-10-12T08:00:00Z")

    fun sesEvent(
        messageId: MessageId,
        event: String,
        recipient: String = "mottaker@rogfk.no",
        timestamp: Instant? = Instant.parse("2026-10-12T08:00:00.123Z"),
        diagnostic: String = "smtp; 550 5.1.1 $recipient: user unknown",
    ): String {
        val tags = """{"ses:configuration-set":["fint-communication"],"messageId":["${messageId.value}"]}"""
        val mail =
            """
            {"timestamp":"$MAIL_TIMESTAMP","source":"no-reply@novari.no","messageId":"0100018f-ses-melding",
             "destination":["$recipient"],"tags":$tags}
            """.trimIndent()
        val time = timestamp?.let { ""","timestamp":"$it"""" }.orEmpty()
        val details =
            when (event) {
                "Delivery" -> {
                    """"delivery":{"recipients":["$recipient"],"smtpResponse":"250 ok"$time}"""
                }

                "Bounce/Permanent", "Bounce/Suppressed", "Bounce/Transient" -> {
                    val (type, subType) =
                        when (event) {
                            "Bounce/Permanent" -> "Permanent" to "General"
                            "Bounce/Suppressed" -> "Permanent" to "Suppressed"
                            else -> "Transient" to "MailboxFull"
                        }
                    """"bounce":{"bounceType":"$type","bounceSubType":"$subType",""" +
                        """"bouncedRecipients":[{"emailAddress":"$recipient","diagnosticCode":"$diagnostic"}]$time}"""
                }

                "Complaint" -> {
                    """"complaint":{"complainedRecipients":[{"emailAddress":"$recipient"}],""" +
                        """"complaintFeedbackType":"abuse"$time}"""
                }

                "Complaint/Suppressed" -> {
                    """"complaint":{"complainedRecipients":[{"emailAddress":"$recipient"}],""" +
                        """"complaintSubType":"OnAccountSuppressionList"$time}"""
                }

                "Reject" -> {
                    """"reject":{"reason":"Bad content"}"""
                }

                else -> {
                    null
                }
            }
        val eventType = event.substringBefore('/')
        return """{"eventType":"$eventType","mail":$mail${details?.let { ",$it" }.orEmpty()}}"""
    }

    fun sns(event: String): String =
        """
        {
          "Type": "Notification",
          "MessageId": "${UUID.randomUUID()}",
          "TopicArn": "arn:aws:sns:eu-north-1:123456789012:fint-communication-ses-events",
          "Message": ${jsonMapper.writeValueAsString(event)},
          "Timestamp": "2026-10-12T08:00:01.000Z"
        }
        """.trimIndent()
}
