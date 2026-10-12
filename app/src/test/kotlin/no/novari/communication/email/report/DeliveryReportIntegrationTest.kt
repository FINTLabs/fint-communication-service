package no.novari.communication.email.report

import com.azure.core.util.Context
import com.azure.storage.queue.QueueClient
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
import org.testcontainers.azure.AzuriteContainer
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
    lateinit var azurite: AzuriteContainer

    private lateinit var queueClient: QueueClient
    private val recipientNumber = AtomicInteger()

    @BeforeEach
    fun setUp() {
        queueClient = StorageDeliveryReportQueue.clientBuilder(azurite.connectionString, QUEUE_NAME).buildClient()
        queueClient.createIfNotExists()
        queueClient.clearMessages()
        jdbcClient.sql("DELETE FROM send_usage").update()
        jdbcClient.sql("DELETE FROM recipient_blocklist").update()
        jdbcClient.sql("DELETE FROM dispatch_queue").update()
        jdbcClient.sql("DELETE FROM message").update()
        emailAdapter.reset()
        clock.set(START)
    }

    @Test
    fun `every ACS status gives the expected status and blocks only bounced and suppressed recipients`() {
        val expected =
            mapOf(
                AcsDeliveryStatus.DELIVERED to (MessageStatus.DELIVERED to null),
                AcsDeliveryStatus.BOUNCED to (MessageStatus.BOUNCED to null),
                AcsDeliveryStatus.SUPPRESSED to (MessageStatus.SUPPRESSED to null),
                AcsDeliveryStatus.FILTERED_SPAM to (MessageStatus.FILTERED_SPAM to null),
                AcsDeliveryStatus.QUARANTINED to (MessageStatus.FILTERED_SPAM to null),
                AcsDeliveryStatus.FAILED to (MessageStatus.FAILED to FailureReason.DELIVERY_FAILED),
                AcsDeliveryStatus.EXPANDED to (MessageStatus.SENT to null),
            )
        val sent = expected.keys.associateWith { status -> nextRecipient().let { it to sendAndDispatch(it) } }
        val before = listOf("BOUNCED", "FILTERED_SPAM", "EXPANDED").associateWith(::received)
        sent.forEach { (status, message) -> enqueue(event(message.second, status.value, message.first)) }

        poller.poll()

        sent.forEach { (status, message) ->
            val (address, id) = message
            val stored = stored(id)
            assertThat(stored.status to stored.failureReason).describedAs(status.value).isEqualTo(expected[status])
            assertThat(blocklistExpiry(address))
                .describedAs(status.value)
                .isEqualTo(if (status.blocksRecipient) OCCURRED_AT + Duration.ofDays(30) else null)
        }
        assertThat(queueClient.properties.approximateMessagesCountLong).isZero()
        assertThat(received("BOUNCED") - before.getValue("BOUNCED")).isEqualTo(1.0)
        assertThat(received("FILTERED_SPAM") - before.getValue("FILTERED_SPAM")).isEqualTo(2.0)
        assertThat(received("EXPANDED") - before.getValue("EXPANDED")).isEqualTo(1.0)
    }

    @Test
    fun `a blocked recipient is rejected on the next message`() {
        val address = nextRecipient()
        enqueue(event(sendAndDispatch(address), "Bounced", address))

        poller.poll()

        assertThat(blocklistSource(address)).isEqualTo("ACS")
        assertThat(runCatching { send(address) }.exceptionOrNull())
            .isInstanceOf(RecipientBlockedException::class.java)
    }

    @Test
    fun `a recipient is blocked only when the status blocks it`() {
        val address = nextRecipient()
        val nonBlocking = AcsDeliveryStatus.entries.filterNot { it.blocksRecipient }

        val processed =
            nonBlocking.map { status ->
                processor.process(
                    DeliveryReport(sendAndDispatch(nextRecipient()), status, hasher.hash(address), OCCURRED_AT),
                )
            }

        assertThat(processed).allSatisfy { assertThat(it.blocked).isFalse() }
        assertThat(blocklistExpiry(address)).isNull()
    }

    @Test
    fun `a duplicate report gives the same result`() {
        val address = nextRecipient()
        val id = sendAndDispatch(address)
        val event = event(id, "Bounced", address)
        enqueue(event)
        poller.poll()
        val first = stored(id)
        val firstExpiry = blocklistExpiry(address)

        clock.advance(Duration.ofHours(1))
        enqueue(event)
        poller.poll()

        assertThat(stored(id)).isEqualTo(first)
        assertThat(blocklistExpiry(address)).isEqualTo(firstExpiry)
        assertThat(queueClient.properties.approximateMessagesCountLong).isZero()
    }

    @Test
    fun `the first report wins`() {
        val address = nextRecipient()
        val id = sendAndDispatch(address)
        enqueue(event(id, "Delivered", address))
        poller.poll()

        enqueue(event(id, "Failed", address))
        poller.poll()

        assertThat(stored(id).status).isEqualTo(MessageStatus.DELIVERED)
        assertThat(stored(id).failureReason).isNull()
    }

    @Test
    fun `a report that arrives before the worker has stored SENT is kept`() {
        val address = nextRecipient()
        val id = send(address)
        emailAdapter.beforeNextResponse {
            enqueue(event(id, "Delivered", address))
            poller.poll()
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
            enqueue(event(id, "Bounced", address))
            poller.poll()
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
        enqueue(event(MessageId.generate(), "Bounced", bounced))
        enqueue(event(MessageId.generate(), "Delivered", delivered))

        poller.poll()

        assertThat(blocklistExpiry(bounced)).isEqualTo(OCCURRED_AT + Duration.ofDays(30))
        assertThat(blocklistExpiry(delivered)).isNull()
        assertThat(discarded(DiscardReason.UNKNOWN_MESSAGE) - before).isEqualTo(2.0)
        assertThat(queueClient.properties.approximateMessagesCountLong).isZero()
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

        enqueue(event(MessageId.generate(), "Bounced", address))
        poller.poll()
        assertThat(blocklistExpiry(address)).isEqualTo(manualExpiry)

        val later = OCCURRED_AT + Duration.ofDays(20)
        enqueue(event(MessageId.generate(), "Suppressed", address, later))
        poller.poll()

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
    fun `a raw JSON event is read as well as a base64 encoded one`() {
        val address = nextRecipient()
        val id = sendAndDispatch(address)
        queueClient.sendMessage(event(id, "Delivered", address))

        poller.poll()

        assertThat(stored(id).status).isEqualTo(MessageStatus.DELIVERED)
    }

    @Test
    fun `an event that keeps coming back is discarded as poison`() {
        val address = nextRecipient()
        val id = sendAndDispatch(address)
        enqueue(event(id, "Delivered", address))
        val before = discarded(DiscardReason.POISON)
        repeat(2) {
            assertThat(queueClient.receiveMessages(1, VISIBILITY, null, Context.NONE).count()).isEqualTo(1)
            Thread.sleep(VISIBILITY.plusMillis(200).toMillis())
        }

        poller.poll()

        assertThat(stored(id).status).isEqualTo(MessageStatus.SENT)
        assertThat(discarded(DiscardReason.POISON) - before).isEqualTo(1.0)
        assertThat(queueClient.properties.approximateMessagesCountLong).isZero()
    }

    @Test
    fun `invalid and unsupported events are discarded`() {
        val before = discarded(DiscardReason.INVALID) to discarded(DiscardReason.UNSUPPORTED_TYPE)
        enqueue("{ikke json")
        enqueue(
            DeliveryReportEvents.cloudEvent(
                MessageId.generate(),
                "Delivered",
                type = "Microsoft.Communication.EmailEngagementTrackingReportReceived",
            ),
        )

        poller.poll()

        assertThat(discarded(DiscardReason.INVALID) - before.first).isEqualTo(1.0)
        assertThat(discarded(DiscardReason.UNSUPPORTED_TYPE) - before.second).isEqualTo(1.0)
        assertThat(queueClient.properties.approximateMessagesCountLong).isZero()
    }

    @Test
    fun `the queue size is published`() {
        repeat(3) { enqueue(event(MessageId.generate(), "Delivered", nextRecipient())) }

        queueMetrics.refresh()

        assertThat(meterRegistry.get(DeliveryReportQueueMetrics.SIZE_METRIC).gauge().value()).isEqualTo(3.0)
    }

    private fun event(
        id: MessageId,
        status: String,
        address: String,
        occurredAt: Instant = OCCURRED_AT,
    ): String = DeliveryReportEvents.cloudEvent(id, status, address, occurredAt)

    private fun enqueue(event: String) {
        queueClient.sendMessage(DeliveryReportEvents.base64(event))
    }

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
        const val QUEUE_NAME = "email-delivery-reports"
        val START: Instant = Instant.parse("2026-10-12T08:00:00Z")
        val OCCURRED_AT: Instant = Instant.parse("2026-10-12T08:00:30.123456Z")
        val VISIBILITY: Duration = Duration.ofSeconds(1)
    }
}
