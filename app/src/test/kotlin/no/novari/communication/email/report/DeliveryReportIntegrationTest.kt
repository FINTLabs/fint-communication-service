package no.novari.communication.email.report

import io.micrometer.core.instrument.MeterRegistry
import no.novari.communication.ControllableEmailAdapter
import no.novari.communication.MutableClock
import no.novari.communication.blocklist.RecipientBlockedException
import no.novari.communication.email.EmailSendOutcome
import no.novari.communication.email.RetryReason
import no.novari.communication.message.MessageService
import no.novari.communication.message.MessageStore
import no.novari.communication.message.StoredMessage
import no.novari.communication.message.dispatch.DispatchWorker
import no.novari.communication.message.domain.EmailPayload
import no.novari.communication.message.domain.MessageId
import no.novari.communication.model.FailureReason
import no.novari.communication.model.MessageStatus
import no.novari.communication.model.Tenant
import no.novari.communication.recipient.RecipientHasher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

@DeliveryReportTest
class DeliveryReportIntegrationTest {
    @Autowired
    lateinit var poller: DeliveryReportPoller

    @Autowired
    lateinit var queueMetrics: DeliveryReportQueueMetrics

    @Autowired
    lateinit var processor: DeliveryReportProcessor

    @Autowired
    lateinit var messageService: MessageService

    @Autowired
    lateinit var messageStore: MessageStore

    @Autowired
    lateinit var dispatchWorker: DispatchWorker

    @Autowired
    lateinit var emailAdapter: ControllableEmailAdapter

    @Autowired
    lateinit var hasher: RecipientHasher

    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    @Autowired
    lateinit var jdbcClient: JdbcClient

    @Autowired
    lateinit var sqs: SqsClient

    @Autowired
    lateinit var queueUrl: DeliveryReportQueueUrl

    private val recipientNumber = AtomicInteger()

    @BeforeEach
    fun setUp() {
        sqs.purgeQueue { it.queueUrl(queueUrl.value) }
        jdbcClient.sql("DELETE FROM send_usage").update()
        jdbcClient.sql("DELETE FROM recipient_blocklist").update()
        jdbcClient.sql("DELETE FROM dispatch_queue").update()
        jdbcClient.sql("DELETE FROM message").update()
        emailAdapter.reset()
        clock.set(START)
    }

    @Test
    fun `every SES event gives the expected status and blocks bounced, suppressed and complaining recipients`() {
        val expected =
            mapOf(
                SesDeliveryEvent.DELIVERED to (MessageStatus.DELIVERED to null),
                SesDeliveryEvent.BOUNCED to (MessageStatus.BOUNCED to null),
                SesDeliveryEvent.SUPPRESSED to (MessageStatus.SUPPRESSED to null),
                SesDeliveryEvent.SOFT_BOUNCED to (MessageStatus.FAILED to FailureReason.DELIVERY_FAILED),
                SesDeliveryEvent.REJECTED to (MessageStatus.FAILED to FailureReason.REJECTED),
                SesDeliveryEvent.COMPLAINED to (MessageStatus.SENT to null),
            )
        val sent = expected.keys.associateWith { event -> nextRecipient().let { it to sendAndDispatch(it) } }
        val before = listOf("BOUNCED", "FAILED", "COMPLAINED").associateWith(::received)
        sent.forEach { (event, message) -> enqueue(event(message.second, event.value, message.first)) }

        pollUntilEmpty()

        sent.forEach { (event, message) ->
            val (address, id) = message
            val stored = stored(id)
            assertThat(stored.status to stored.failureReason).describedAs(event.value).isEqualTo(expected[event])
            assertThat(blocklistReason(address)).describedAs(event.value).isEqualTo(event.blocks?.name)
        }
        assertThat(blocklistExpiry(sent.getValue(SesDeliveryEvent.BOUNCED).first))
            .isEqualTo(OCCURRED_AT + Duration.ofDays(30))
        assertThat(queueSize()).isZero()
        assertThat(received("BOUNCED") - before.getValue("BOUNCED")).isEqualTo(1.0)
        assertThat(received("FAILED") - before.getValue("FAILED")).isEqualTo(2.0)
        assertThat(received("COMPLAINED") - before.getValue("COMPLAINED")).isEqualTo(1.0)
    }

    @Test
    fun `a complaint after delivery keeps the status and blocks the recipient as opt-out`() {
        val address = nextRecipient()
        val id = sendAndDispatch(address)
        enqueue(event(id, "Delivery", address))
        pollUntilEmpty()

        enqueue(event(id, "Complaint", address))
        pollUntilEmpty()

        assertThat(stored(id).status).isEqualTo(MessageStatus.DELIVERED)
        assertThat(blocklistReason(address)).isEqualTo("OPT_OUT")
        assertThat(runCatching { send(address) }.exceptionOrNull())
            .isInstanceOf(RecipientBlockedException::class.java)
    }

    @Test
    fun `a blocked recipient is rejected on the next message`() {
        val address = nextRecipient()
        enqueue(event(sendAndDispatch(address), "Bounce/Permanent", address))

        pollUntilEmpty()

        assertThat(blocklistSource(address)).isEqualTo("SES")
        assertThat(runCatching { send(address) }.exceptionOrNull())
            .isInstanceOf(RecipientBlockedException::class.java)
    }

    @Test
    fun `a recipient is blocked only when the status blocks it`() {
        val address = nextRecipient()
        val nonBlocking = SesDeliveryEvent.entries.filter { it.blocks == null }

        val processed =
            nonBlocking.map { event ->
                processor.process(
                    DeliveryReport(sendAndDispatch(nextRecipient()), event, hasher.hash(address), OCCURRED_AT),
                )
            }

        assertThat(processed).allSatisfy { assertThat(it.blocked).isFalse() }
        assertThat(blocklistReason(address)).isNull()
    }

    @Test
    fun `a duplicate report gives the same result`() {
        val address = nextRecipient()
        val id = sendAndDispatch(address)
        val event = event(id, "Bounce/Permanent", address)
        enqueue(event)
        pollUntilEmpty()
        val first = stored(id)
        val firstExpiry = blocklistExpiry(address)

        clock.advance(Duration.ofHours(1))
        enqueue(event)
        pollUntilEmpty()

        assertThat(stored(id)).isEqualTo(first)
        assertThat(blocklistExpiry(address)).isEqualTo(firstExpiry)
        assertThat(queueSize()).isZero()
    }

    @Test
    fun `the first report wins`() {
        val address = nextRecipient()
        val id = sendAndDispatch(address)
        enqueue(event(id, "Delivery", address))
        pollUntilEmpty()

        enqueue(event(id, "Bounce/Transient", address))
        pollUntilEmpty()

        assertThat(stored(id).status).isEqualTo(MessageStatus.DELIVERED)
        assertThat(stored(id).failureReason).isNull()
    }

    @Test
    fun `a report that arrives before the worker has stored SENT is kept`() {
        val address = nextRecipient()
        val id = send(address)
        emailAdapter.beforeNextResponse {
            enqueue(event(id, "Delivery", address))
            pollUntilEmpty()
        }

        dispatchWorker.processDue()

        assertThat(stored(id).status).isEqualTo(MessageStatus.DELIVERED)
        assertThat(queuedMessages()).isZero()
    }

    @Test
    fun `a message is not sent again when a report has arrived during a failed attempt`() {
        val address = nextRecipient()
        val id = send(address)
        emailAdapter.respondWith(EmailSendOutcome.Retryable(RetryReason.TIMEOUT, "ingen sluttstatus"))
        emailAdapter.beforeNextResponse {
            enqueue(event(id, "Bounce/Permanent", address))
            pollUntilEmpty()
        }

        dispatchWorker.processDue()
        clock.advance(Duration.ofHours(1))
        dispatchWorker.processDue()

        assertThat(emailAdapter.sent).hasSize(1)
        assertThat(stored(id).status).isEqualTo(MessageStatus.BOUNCED)
        assertThat(queuedMessages()).isZero()
    }

    @Test
    fun `a report for an unknown message is discarded but still blocks a bounced recipient`() {
        val bounced = nextRecipient()
        val delivered = nextRecipient()
        val before = discarded(DiscardReason.UNKNOWN_MESSAGE)
        enqueue(event(MessageId.generate(), "Bounce/Permanent", bounced))
        enqueue(event(MessageId.generate(), "Delivery", delivered))

        pollUntilEmpty()

        assertThat(blocklistExpiry(bounced)).isEqualTo(OCCURRED_AT + Duration.ofDays(30))
        assertThat(blocklistExpiry(delivered)).isNull()
        assertThat(discarded(DiscardReason.UNKNOWN_MESSAGE) - before).isEqualTo(2.0)
        assertThat(queueSize()).isZero()
    }

    @Test
    fun `a bounce extends the blocking but never shortens it and leaves an opt-out alone`() {
        val address = nextRecipient()
        val hash = hasher.hash(address).value
        val manualExpiry = OCCURRED_AT + Duration.ofDays(40)
        jdbcClient
            .sql(
                """
                INSERT INTO recipient_blocklist (recipient_hash, reason, source, expires_at)
                VALUES (:hash, 'HARD_BOUNCE', 'MANUAL', :expiresAt), (:hash, 'OPT_OUT', 'MANUAL', NULL)
                """.trimIndent(),
            ).param("hash", hash)
            .param("expiresAt", OffsetDateTime.ofInstant(manualExpiry, ZoneOffset.UTC))
            .update()

        enqueue(event(MessageId.generate(), "Bounce/Permanent", address))
        pollUntilEmpty()
        assertThat(blocklistExpiry(address)).isEqualTo(manualExpiry)

        val later = OCCURRED_AT + Duration.ofDays(20)
        enqueue(event(MessageId.generate(), "Bounce/Suppressed", address, later))
        pollUntilEmpty()

        assertThat(blocklistExpiry(address)).isEqualTo(later + Duration.ofDays(30))
        assertThat(
            jdbcClient
                .sql("SELECT count(*) FROM recipient_blocklist WHERE recipient_hash = :hash AND reason = 'OPT_OUT'")
                .param("hash", hash)
                .query(Long::class.java)
                .single(),
        ).isEqualTo(1L)
    }

    @Test
    fun `a raw event is read as well as one in an SNS notification`() {
        val address = nextRecipient()
        val id = sendAndDispatch(address)
        sendToQueue(event(id, "Delivery", address))

        pollUntilEmpty()

        assertThat(stored(id).status).isEqualTo(MessageStatus.DELIVERED)
    }

    @Test
    fun `an event that keeps coming back is discarded as poison`() {
        val address = nextRecipient()
        val id = sendAndDispatch(address)
        enqueue(event(id, "Delivery", address))
        val before = discarded(DiscardReason.POISON)
        repeat(2) {
            assertThat(
                sqs
                    .receiveMessage {
                        it.queueUrl(queueUrl.value).visibilityTimeout(VISIBILITY.toSeconds().toInt())
                    }.messages(),
            ).hasSize(1)
            Thread.sleep(VISIBILITY.plusMillis(200).toMillis())
        }

        pollUntilEmpty()

        assertThat(stored(id).status).isEqualTo(MessageStatus.SENT)
        assertThat(discarded(DiscardReason.POISON) - before).isEqualTo(1.0)
        assertThat(queueSize()).isZero()
    }

    @Test
    fun `invalid and unsupported events are discarded`() {
        val before = discarded(DiscardReason.INVALID) to discarded(DiscardReason.UNSUPPORTED_TYPE)
        enqueue("{ikke json")
        enqueue(DeliveryReportEvents.sesEvent(MessageId.generate(), "Open"))

        pollUntilEmpty()

        assertThat(discarded(DiscardReason.INVALID) - before.first).isEqualTo(1.0)
        assertThat(discarded(DiscardReason.UNSUPPORTED_TYPE) - before.second).isEqualTo(1.0)
        assertThat(queueSize()).isZero()
    }

    @Test
    fun `the queue size is published`() {
        repeat(3) { enqueue(event(MessageId.generate(), "Delivery", nextRecipient())) }

        queueMetrics.refresh()

        assertThat(meterRegistry.get(DeliveryReportQueueMetrics.SIZE_METRIC).gauge().value()).isEqualTo(3.0)
    }

    private fun event(
        id: MessageId,
        status: String,
        address: String,
        occurredAt: Instant = OCCURRED_AT,
    ): String = DeliveryReportEvents.sesEvent(id, status, address, occurredAt)

    private fun enqueue(event: String) {
        sendToQueue(DeliveryReportEvents.sns(event))
    }

    private fun sendToQueue(body: String) {
        sqs.sendMessage { it.queueUrl(queueUrl.value).messageBody(body) }
    }

    private fun pollUntilEmpty() {
        repeat(MAX_POLLS) {
            poller.poll()
            if (queueSize() == 0L) return
        }
    }

    private fun queueSize(): Long =
        sqs
            .getQueueAttributes {
                it
                    .queueUrl(queueUrl.value)
                    .attributeNames(
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
                    )
            }.attributes()
            .values
            .sumOf { it.toLong() }

    private fun nextRecipient(): String = "mottaker-${recipientNumber.incrementAndGet()}@rogfk.no"

    private fun send(address: String): MessageId =
        messageService.receive(
            Tenant.ROGALAND,
            EmailPayload(templateId = "team/varsel", to = address, subject = "Emne", body = "Innhold"),
        )

    private fun sendAndDispatch(address: String): MessageId {
        val id = send(address)
        dispatchWorker.processDue()
        assertThat(stored(id).status).isEqualTo(MessageStatus.SENT)
        return id
    }

    private fun stored(id: MessageId): StoredMessage = checkNotNull(messageStore.find(id))

    private fun queuedMessages(): Long =
        jdbcClient.sql("SELECT count(*) FROM dispatch_queue").query(Long::class.java).single()

    private fun blocklistExpiry(address: String): Instant? =
        jdbcClient
            .sql("SELECT expires_at FROM recipient_blocklist WHERE recipient_hash = :hash AND reason = 'HARD_BOUNCE'")
            .param("hash", hasher.hash(address).value)
            .query { rs, _ -> rs.getObject("expires_at", OffsetDateTime::class.java).toInstant() }
            .optional()
            .orElse(null)

    private fun blocklistReason(address: String): String? =
        jdbcClient
            .sql("SELECT reason FROM recipient_blocklist WHERE recipient_hash = :hash")
            .param("hash", hasher.hash(address).value)
            .query(String::class.java)
            .optional()
            .orElse(null)

    private fun blocklistSource(address: String): String? =
        jdbcClient
            .sql("SELECT source FROM recipient_blocklist WHERE recipient_hash = :hash AND reason = 'HARD_BOUNCE'")
            .param("hash", hasher.hash(address).value)
            .query(String::class.java)
            .optional()
            .orElse(null)

    private fun received(status: String): Double =
        meterRegistry
            .find(DeliveryReportMetrics.RECEIVED_METRIC)
            .tags("status", status, "tenant", "ROGALAND", "channel", "EMAIL", "template", "team/varsel")
            .counters()
            .sumOf { it.count() }

    private fun discarded(reason: DiscardReason): Double =
        meterRegistry
            .find(DeliveryReportMetrics.DISCARDED_METRIC)
            .tag("reason", reason.value)
            .counter()
            ?.count() ?: 0.0

    private companion object {
        val START: Instant = Instant.parse("2026-10-12T08:00:00Z")
        val OCCURRED_AT: Instant = Instant.parse("2026-10-12T08:00:30.123456Z")
        val VISIBILITY: Duration = Duration.ofSeconds(1)
        const val MAX_POLLS = 5
    }
}
