package no.novari.communication.message

import no.novari.communication.message.domain.EmailPayload
import no.novari.communication.message.domain.MessageStatus
import no.novari.communication.message.domain.OutgoingMessage
import no.novari.communication.model.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class MessageServiceTest {
    private val now = Instant.parse("2026-10-06T12:00:00Z")
    private val dispatched = mutableListOf<OutgoingMessage>()
    private val service = MessageService(dispatched::add, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `a received message is dispatched as received and its id is returned`() {
        val payload = EmailPayload(templateId = "team/mal", to = "ola@rogfk.no", subject = "Emne", body = "Innhold")

        val id = service.receive(Tenant.ROGALAND, payload)

        assertThat(dispatched).singleElement().satisfies({ message ->
            assertThat(message.id).isEqualTo(id)
            assertThat(message.tenant).isEqualTo(Tenant.ROGALAND)
            assertThat(message.payload).isEqualTo(payload)
            assertThat(message.status).isEqualTo(MessageStatus.RECEIVED)
            assertThat(message.receivedAt).isEqualTo(now)
        })
    }
}
