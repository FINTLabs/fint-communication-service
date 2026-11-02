package no.novari.communication.email.report

import no.novari.communication.TEST_RECIPIENT_HASHING_KEY
import no.novari.communication.message.domain.MessageId
import no.novari.communication.recipient.RecipientHasher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

class DeliveryReportParserTest {
    private val hasher = RecipientHasher.fromBase64Key(TEST_RECIPIENT_HASHING_KEY)
    private val parser = DeliveryReportParser(JsonMapper.builder().build(), hasher)
    private val messageId = MessageId.generate()

    @ParameterizedTest
    @EnumSource(SesDeliveryEvent::class)
    fun `reads every SES event in an SNS notification`(event: SesDeliveryEvent) {
        val parsed = parse(DeliveryReportEvents.sns(DeliveryReportEvents.sesEvent(messageId, event.value)))

        assertThat(parsed).isEqualTo(
            ParsedReport.Report(
                DeliveryReport(
                    messageId = messageId,
                    event = event,
                    recipient = if (event.blocks != null) hasher.hash("mottaker@rogfk.no") else null,
                    occurredAt =
                        if (event == SesDeliveryEvent.REJECTED) {
                            DeliveryReportEvents.MAIL_TIMESTAMP
                        } else {
                            Instant.parse("2026-10-12T08:00:00.123Z")
                        },
                ),
            ),
        )
    }

    @Test
    fun `reads a raw event without SNS notification`() {
        val event = DeliveryReportEvents.sesEvent(messageId, "Bounce/Permanent")

        assertThat(parse(event))
            .isEqualTo(parse(DeliveryReportEvents.sns(event)))
            .isInstanceOf(ParsedReport.Report::class.java)
    }

    @Test
    fun `an undetermined bounce is a soft bounce and an account suppression is suppressed`() {
        val bounce = DeliveryReportEvents.sesEvent(messageId, "Bounce/Transient")
        val undetermined = bounce.replace("\"Transient\"", "\"Undetermined\"")
        val onAccountList =
            DeliveryReportEvents
                .sesEvent(messageId, "Bounce/Suppressed")
                .replace("\"Suppressed\"", "\"OnAccountSuppressionList\"")

        assertThat((parse(undetermined) as ParsedReport.Report).report.event).isEqualTo(SesDeliveryEvent.SOFT_BOUNCED)
        assertThat((parse(onAccountList) as ParsedReport.Report).report.event).isEqualTo(SesDeliveryEvent.SUPPRESSED)
    }

    @Test
    fun `uses the time of the mail when the event has no timestamp`() {
        val parsed = parse(DeliveryReportEvents.sesEvent(messageId, "Delivery", timestamp = null))

        assertThat((parsed as ParsedReport.Report).report.occurredAt).isEqualTo(DeliveryReportEvents.MAIL_TIMESTAMP)
    }

    @Test
    fun `hashes the recipient only when the report blocks it`() {
        val bounced = parse(DeliveryReportEvents.sesEvent(messageId, "Bounce/Permanent", recipient = " Ola@RogFK.no"))
        val complained = parse(DeliveryReportEvents.sesEvent(messageId, "Complaint", recipient = "Ola@rogfk.no"))
        val delivered = parse(DeliveryReportEvents.sesEvent(messageId, "Delivery"))

        assertThat((bounced as ParsedReport.Report).report.recipient).isEqualTo(hasher.hash("ola@rogfk.no"))
        assertThat((complained as ParsedReport.Report).report.recipient).isEqualTo(hasher.hash("ola@rogfk.no"))
        assertThat((delivered as ParsedReport.Report).report.recipient).isNull()
    }

    @Test
    fun `other event types are unsupported`() {
        listOf("Send", "Open", "Click", "DeliveryDelay", "RenderingFailure", "Subscription").forEach {
            assertThat(
                parse(DeliveryReportEvents.sesEvent(messageId, it)),
            ).describedAs(it).isEqualTo(ParsedReport.Unsupported)
        }
    }

    @Test
    fun `an event without the required fields is invalid`() {
        val valid = DeliveryReportEvents.sesEvent(messageId, "Delivery")

        listOf(
            valid.replace("\"${messageId.value}\"", "\"ikke-en-uuid\""),
            valid.replace("\"messageId\":[", "\"annenTag\":["),
            valid.replace("\"eventType\":\"Delivery\",", ""),
            DeliveryReportEvents.sesEvent(messageId, "Bounce/Permanent").replace("\"Permanent\"", "\"Ukjent\""),
            DeliveryReportEvents
                .sesEvent(messageId, "Delivery", timestamp = null)
                .replace("\"timestamp\":\"${DeliveryReportEvents.MAIL_TIMESTAMP}\",", ""),
        ).forEach { assertThat(parse(it)).isEqualTo(ParsedReport.Invalid) }
    }

    @Test
    fun `a body that is not a JSON object is invalid`() {
        listOf("{ikke json", "[]", "\"tekst\"", DeliveryReportEvents.sns("ikke json"))
            .forEach { assertThat(parse(it)).describedAs(it).isEqualTo(ParsedReport.Invalid) }
    }

    @Test
    fun `the parsed report does not expose the recipient`() {
        val parsed = parse(DeliveryReportEvents.sesEvent(messageId, "Bounce/Permanent", recipient = "ola@rogfk.no"))

        assertThat(parsed.toString())
            .doesNotContain("ola@rogfk.no")
            .doesNotContain(hasher.hash("ola@rogfk.no").value)
    }

    private fun parse(body: String): ParsedReport = parser.parse(body.toByteArray())
}
