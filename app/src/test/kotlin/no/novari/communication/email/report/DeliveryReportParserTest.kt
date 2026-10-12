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
    @EnumSource(AcsDeliveryStatus::class)
    fun `reads every ACS status`(status: AcsDeliveryStatus) {
        val parsed = parse(DeliveryReportEvents.cloudEvent(messageId, status.value))

        assertThat(parsed).isEqualTo(
            ParsedReport.Report(
                DeliveryReport(
                    messageId = messageId,
                    status = status,
                    recipient = if (status.blocksRecipient) hasher.hash("mottaker@rogfk.no") else null,
                    occurredAt = Instant.parse("2026-10-12T08:00:00.1234567Z"),
                ),
            ),
        )
    }

    @Test
    fun `reads a base64 encoded event`() {
        val event = DeliveryReportEvents.cloudEvent(messageId, "Bounced")

        assertThat(parser.parse(DeliveryReportEvents.base64(event).toByteArray()))
            .isEqualTo(parse(event))
            .isInstanceOf(ParsedReport.Report::class.java)
    }

    @Test
    fun `reads an event in an array with one element`() {
        val event = DeliveryReportEvents.cloudEvent(messageId, "Delivered")

        assertThat(parse("[$event]")).isEqualTo(parse(event)).isInstanceOf(ParsedReport.Report::class.java)
    }

    @Test
    fun `uses the time of the event when the delivery attempt has no timestamp`() {
        val parsed = parse(DeliveryReportEvents.cloudEvent(messageId, "Delivered", deliveryAttemptTimeStamp = null))

        assertThat((parsed as ParsedReport.Report).report.occurredAt).isEqualTo(Instant.parse("2026-10-12T08:00:01Z"))
    }

    @Test
    fun `hashes the recipient only when the report blocks it`() {
        val bounced = parse(DeliveryReportEvents.cloudEvent(messageId, "Bounced", recipient = " Ola@RogFK.no"))
        val delivered = parse(DeliveryReportEvents.cloudEvent(messageId, "Delivered"))

        assertThat((bounced as ParsedReport.Report).report.recipient).isEqualTo(hasher.hash("ola@rogfk.no"))
        assertThat((delivered as ParsedReport.Report).report.recipient).isNull()
    }

    @Test
    fun `other event types are unsupported`() {
        val event =
            DeliveryReportEvents.cloudEvent(
                messageId,
                "Delivered",
                type = "Microsoft.Communication.EmailEngagementTrackingReportReceived",
            )

        assertThat(parse(event)).isEqualTo(ParsedReport.Unsupported)
    }

    @Test
    fun `an event without the required fields is invalid`() {
        val valid = DeliveryReportEvents.cloudEvent(messageId, "Delivered")

        listOf(
            valid.replace("\"${messageId.value}\"", "\"ikke-en-uuid\""),
            valid.replace("\"Delivered\"", "\"Opened\""),
            valid.replace("\"status\": \"Delivered\",", ""),
            DeliveryReportEvents
                .cloudEvent(messageId, "Delivered", deliveryAttemptTimeStamp = null)
                .replace("\"time\": \"2026-10-12T08:00:01Z\",", ""),
        ).forEach { assertThat(parse(it)).isEqualTo(ParsedReport.Invalid) }
    }

    @Test
    fun `a body that is neither JSON nor base64 is invalid`() {
        listOf("{ikke json", "ikke base64 ###", DeliveryReportEvents.base64("ikke json"), "[]", "\"tekst\"")
            .forEach { assertThat(parse(it)).isEqualTo(ParsedReport.Invalid) }
    }

    @Test
    fun `the parsed report does not expose the recipient`() {
        val parsed = parse(DeliveryReportEvents.cloudEvent(messageId, "Bounced", recipient = "ola@rogfk.no"))

        assertThat(parsed.toString())
            .doesNotContain("ola@rogfk.no")
            .doesNotContain(hasher.hash("ola@rogfk.no").value)
    }

    private fun parse(body: String): ParsedReport = parser.parse(body.toByteArray())
}
