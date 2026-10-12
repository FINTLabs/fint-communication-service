package no.novari.communication.email.report

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.novari.communication.TEST_RECIPIENT_HASHING_KEY
import no.novari.communication.blocklist.BlocklistRepository
import no.novari.communication.message.MessageStore
import no.novari.communication.message.ReportOutcome
import no.novari.communication.message.StoredMessage
import no.novari.communication.message.domain.MessageChannel
import no.novari.communication.message.domain.MessageId
import no.novari.communication.message.domain.OutgoingMessage
import no.novari.communication.model.FailureReason
import no.novari.communication.model.MessageStatus
import no.novari.communication.model.Tenant
import no.novari.communication.recipient.RecipientHasher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.springframework.transaction.support.TransactionOperations
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant

class DeliveryReportPollerTest {
    private val queue = InMemoryQueue()
    private val store = FakeMessageStore()
    private val meterRegistry = SimpleMeterRegistry()
    private val properties = DeliveryReportProperties(enabled = true, maxDequeueCount = 2)
    private val poller =
        DeliveryReportPoller(
            queue = queue,
            parser =
                DeliveryReportParser(
                    JsonMapper.builder().build(),
                    RecipientHasher.fromBase64Key(TEST_RECIPIENT_HASHING_KEY),
                ),
            processor =
                DeliveryReportProcessor(
                    messageStore = store,
                    blocklist = BlocklistRepository(JdbcClient.create(SimpleDriverDataSource())),
                    transactions = TransactionOperations.withoutTransaction(),
                    blockFor = properties.blockFor,
                    clock = Clock.systemUTC(),
                ),
            metrics = DeliveryReportMetrics(meterRegistry),
            properties = properties,
        )

    @Test
    fun `a report is deleted from the queue after it is stored`() {
        val message = store.add()
        queue.add(DeliveryReportEvents.cloudEvent(message.id, "Delivered"))

        poller.poll()

        assertThat(store.applied).containsExactly(message.id to MessageStatus.DELIVERED)
        assertThat(queue.messages).isEmpty()
        assertThat(received("DELIVERED")).isEqualTo(1.0)
    }

    @Test
    fun `a report that cannot be stored stays in the queue`() {
        val message = store.add()
        store.failure = DataAccessResourceFailureException("databasen er nede")
        queue.add(DeliveryReportEvents.cloudEvent(message.id, "Delivered"))

        poller.poll()

        assertThat(queue.messages).hasSize(1)
        assertThat(meterRegistry.find(DeliveryReportMetrics.RECEIVED_METRIC).counters()).isEmpty()
    }

    @Test
    fun `a report that has been received too many times is discarded as poison`() {
        val message = store.add()
        queue.add(DeliveryReportEvents.cloudEvent(message.id, "Delivered"), dequeueCount = 3)

        poller.poll()

        assertThat(queue.messages).isEmpty()
        assertThat(store.applied).isEmpty()
        assertThat(discarded(DiscardReason.POISON)).isEqualTo(1.0)
    }

    @Test
    fun `invalid and unsupported events are discarded`() {
        queue.add("{ikke json")
        queue.add(
            DeliveryReportEvents.cloudEvent(
                MessageId.generate(),
                "Delivered",
                type = "Microsoft.Communication.EmailEngagementTrackingReportReceived",
            ),
        )

        poller.poll()

        assertThat(queue.messages).isEmpty()
        assertThat(discarded(DiscardReason.INVALID)).isEqualTo(1.0)
        assertThat(discarded(DiscardReason.UNSUPPORTED_TYPE)).isEqualTo(1.0)
    }

    @Test
    fun `a report for an unknown message is discarded`() {
        queue.add(DeliveryReportEvents.cloudEvent(MessageId.generate(), "Delivered"))

        poller.poll()

        assertThat(queue.messages).isEmpty()
        assertThat(discarded(DiscardReason.UNKNOWN_MESSAGE)).isEqualTo(1.0)
    }

    @Test
    fun `an unavailable queue is counted and does not throw`() {
        queue.failure = IllegalStateException("køen svarer ikke")

        poller.poll()

        assertThat(meterRegistry.counter(DeliveryReportMetrics.POLL_FAILED_METRIC).count()).isEqualTo(1.0)
    }

    @Test
    fun `a failed delete is counted and does not throw`() {
        val message = store.add()
        queue.add(DeliveryReportEvents.cloudEvent(message.id, "Delivered"))
        queue.deleteFailure = IllegalStateException("køen svarer ikke")

        poller.poll()

        assertThat(store.applied).hasSize(1)
        assertThat(meterRegistry.counter(DeliveryReportMetrics.POLL_FAILED_METRIC).count()).isEqualTo(1.0)
    }

    private fun received(status: String): Double =
        meterRegistry
            .find(DeliveryReportMetrics.RECEIVED_METRIC)
            .tag("status", status)
            .counter()
            ?.count() ?: 0.0

    private fun discarded(reason: DiscardReason): Double =
        meterRegistry
            .find(DeliveryReportMetrics.DISCARDED_METRIC)
            .tag("reason", reason.value)
            .counter()
            ?.count() ?: 0.0

    private class InMemoryQueue : DeliveryReportQueue {
        val messages = mutableListOf<QueuedReport>()
        var failure: RuntimeException? = null
        var deleteFailure: RuntimeException? = null

        fun add(
            body: String,
            dequeueCount: Long = 1,
        ) {
            messages += QueuedReport("${messages.size}", "pop", dequeueCount, body.toByteArray())
        }

        override fun receive(
            maxMessages: Int,
            visibilityTimeout: Duration,
        ): List<QueuedReport> {
            failure?.let { throw it }
            return messages.take(maxMessages)
        }

        override fun delete(report: QueuedReport) {
            deleteFailure?.let { throw it }
            messages.remove(report)
        }

        override fun approximateSize(): Long = messages.size.toLong()
    }

    private class FakeMessageStore : MessageStore {
        val messages = mutableMapOf<MessageId, StoredMessage>()
        val applied = mutableListOf<Pair<MessageId, MessageStatus>>()
        var failure: RuntimeException? = null

        fun add(): StoredMessage {
            val now = Instant.now()
            val message =
                StoredMessage(
                    MessageId.generate(),
                    Tenant.ROGALAND,
                    MessageChannel.EMAIL,
                    "team/varsel",
                    MessageStatus.SENT,
                    null,
                    now,
                    now,
                )
            messages[message.id] = message
            return message
        }

        override fun save(message: OutgoingMessage) = throw UnsupportedOperationException()

        override fun find(id: MessageId): StoredMessage? = messages[id]

        override fun complete(
            id: MessageId,
            status: MessageStatus,
            failureReason: FailureReason?,
            at: Instant,
        ) = throw UnsupportedOperationException()

        override fun applyReport(
            id: MessageId,
            status: MessageStatus,
            failureReason: FailureReason?,
            at: Instant,
        ): ReportOutcome {
            failure?.let { throw it }
            val message = messages[id] ?: return ReportOutcome.NotFound
            applied += id to status
            return ReportOutcome.Applied(message.copy(status = status, failureReason = failureReason, updatedAt = at))
        }
    }
}
