package no.novari.communication.message.domain

import no.novari.communication.model.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class OutgoingMessageTest {
    private val tenant = Tenant.ROGALAND
    private val payload =
        EmailPayload(
            templateId = "team/mal",
            to = "ola.nordmann@rogfk.no",
            subject = "Emne",
            body = "Innhold",
        )
    private val now = Instant.parse("2026-10-06T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `a received message has status received, the given tenant and payload, and the time from the clock`() {
        val message = OutgoingMessage.receive(tenant, payload, clock)

        assertThat(message.status).isEqualTo(MessageStatus.RECEIVED)
        assertThat(message.tenant).isEqualTo(tenant)
        assertThat(message.payload).isEqualTo(payload)
        assertThat(message.receivedAt).isEqualTo(now)
    }

    @Test
    fun `the channel is derived from the payload`() {
        assertThat(OutgoingMessage.receive(tenant, payload, clock).channel).isEqualTo(MessageChannel.EMAIL)
    }

    @Test
    fun `every received message gets its own id`() {
        val ids = (1..100).map { OutgoingMessage.receive(tenant, payload, clock).id }

        assertThat(ids).doesNotHaveDuplicates()
    }

    @Test
    fun `toString does not expose personal data from the payload`() {
        assertThat(OutgoingMessage.receive(tenant, payload, clock).toString())
            .doesNotContain("ola.nordmann")
            .contains("ROGALAND")
    }
}
