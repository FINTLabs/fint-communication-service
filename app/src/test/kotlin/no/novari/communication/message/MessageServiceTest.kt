package no.novari.communication.message

import no.novari.communication.TEST_RECIPIENT_HASHING_KEY
import no.novari.communication.blocklist.RecipientBlockedException
import no.novari.communication.limit.LimitExceededException
import no.novari.communication.limit.LimitType
import no.novari.communication.message.domain.EmailPayload
import no.novari.communication.message.domain.MessageChannel
import no.novari.communication.message.domain.MessageId
import no.novari.communication.message.domain.OutgoingMessage
import no.novari.communication.model.FailureReason
import no.novari.communication.model.MessageStatus
import no.novari.communication.model.Tenant
import no.novari.communication.recipient.RecipientHash
import no.novari.communication.recipient.RecipientHasher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

class MessageServiceTest {
    private val now = Instant.parse("2026-10-06T12:00:00Z")
    private val hasher = RecipientHasher(Base64.getDecoder().decode(TEST_RECIPIENT_HASHING_KEY))
    private val dispatched = mutableListOf<OutgoingMessage>()
    private val saved = mutableListOf<OutgoingMessage>()
    private val steps = mutableListOf<String>()
    private val limited = mutableListOf<Pair<OutgoingMessage, RecipientHash>>()
    private val events = mutableListOf<Any>()
    private var limitExceeded = false
    private var blocked = false
    private val service =
        MessageService(
            messageStore =
                object : MessageStore {
                    override fun save(message: OutgoingMessage) {
                        steps += "save"
                        saved += message
                    }

                    override fun find(id: MessageId): StoredMessage? = null

                    override fun complete(
                        id: MessageId,
                        status: MessageStatus,
                        failureReason: FailureReason?,
                        at: Instant,
                    ) = throw UnsupportedOperationException()
                },
            dispatcher = { message ->
                steps += "dispatch"
                dispatched += message
            },
            recipientHasher = hasher,
            recipientBlocklist = { message, _ ->
                if (blocked) throw RecipientBlockedException(message.tenant, message.id)
            },
            sendLimiter = { message, recipient ->
                if (limitExceeded) {
                    throw LimitExceededException(LimitType.RECIPIENT, message.tenant, message.id, Duration.ofMinutes(1))
                }
                limited += message to recipient
            },
            eventPublisher = events::add,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
    private val payload = EmailPayload(templateId = "team/mal", to = "ola@rogfk.no", subject = "Emne", body = "Innhold")

    @Test
    fun `a received message is dispatched as received and its id is returned`() {
        val id = service.receive(Tenant.ROGALAND, payload)

        assertThat(dispatched).singleElement().satisfies({ message ->
            assertThat(message.id).isEqualTo(id)
            assertThat(message.tenant).isEqualTo(Tenant.ROGALAND)
            assertThat(message.payload).isEqualTo(payload)
            assertThat(message.status).isEqualTo(MessageStatus.RECEIVED)
            assertThat(message.receivedAt).isEqualTo(now)
        })
    }

    @Test
    fun `the message is saved before it is dispatched`() {
        service.receive(Tenant.ROGALAND, payload)

        assertThat(saved).isEqualTo(dispatched)
        assertThat(steps).containsExactly("save", "dispatch")
    }

    @Test
    fun `an unknown message id is not found`() {
        val id = MessageId.generate()

        assertThatThrownBy { service.find(id) }
            .isInstanceOfSatisfying(MessageNotFoundException::class.java) { assertThat(it.messageId).isEqualTo(id) }
    }

    @Test
    fun `the limit is checked for the hashed recipient before dispatch`() {
        service.receive(Tenant.ROGALAND, payload)

        val (message, recipient) = limited.single()
        assertThat(message).isEqualTo(dispatched.single())
        assertThat(recipient).isEqualTo(hasher.hash("ola@rogfk.no"))
    }

    @Test
    fun `a message over the limit is not dispatched`() {
        limitExceeded = true

        assertThatThrownBy { service.receive(Tenant.ROGALAND, payload) }
            .isInstanceOf(LimitExceededException::class.java)
        assertThat(saved).isEmpty()
        assertThat(dispatched).isEmpty()
    }

    @Test
    fun `a blocked recipient is neither counted nor dispatched`() {
        blocked = true

        assertThatThrownBy { service.receive(Tenant.ROGALAND, payload) }
            .isInstanceOf(RecipientBlockedException::class.java)
        assertThat(limited).isEmpty()
        assertThat(saved).isEmpty()
        assertThat(dispatched).isEmpty()
    }

    @Test
    fun `an accepted message is published with tenant and channel`() {
        service.receive(Tenant.ROGALAND, payload)

        assertThat(events).containsExactly(MessageAccepted(Tenant.ROGALAND, MessageChannel.EMAIL))
    }

    @Test
    fun `a rejected or failed message is not published as accepted`() {
        limitExceeded = true
        assertThatThrownBy { service.receive(Tenant.ROGALAND, payload) }
        limitExceeded = false
        blocked = true
        assertThatThrownBy { service.receive(Tenant.ROGALAND, payload) }

        assertThat(events).isEmpty()
    }
}
