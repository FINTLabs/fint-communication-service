package no.novari.communication.message.dispatch

import io.micrometer.core.instrument.MeterRegistry
import no.novari.communication.ControllableEmailAdapter
import no.novari.communication.IntegrationTest
import no.novari.communication.MutableClock
import no.novari.communication.email.EmailSendOutcome
import no.novari.communication.email.FailureReason
import no.novari.communication.email.RetryReason
import no.novari.communication.limit.LimitExceededException
import no.novari.communication.message.MessageService
import no.novari.communication.message.domain.EmailPayload
import no.novari.communication.message.domain.MessageId
import no.novari.communication.model.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@IntegrationTest
@TestPropertySource(
    properties = [
        "communication.dispatch.max-attempts=3",
        "communication.dispatch.initial-backoff=1m",
        "communication.dispatch.max-backoff=90s",
        "communication.dispatch.lease=5m",
        "communication.dispatch.batch-size=5",
    ],
)
class DispatchWorkerIntegrationTest {
    @Autowired
    lateinit var messageService: MessageService

    @Autowired
    lateinit var worker: DispatchWorker

    @Autowired
    lateinit var repository: DispatchQueueRepository

    @Autowired
    lateinit var queueMetrics: DispatchQueueMetrics

    @Autowired
    lateinit var emailAdapter: ControllableEmailAdapter

    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    @Autowired
    lateinit var jdbcClient: JdbcClient

    private val recipientNumber = AtomicInteger()

    @BeforeEach
    fun setUp() {
        jdbcClient.sql("DELETE FROM send_usage").update()
        jdbcClient.sql("DELETE FROM dispatch_queue").update()
        emailAdapter.reset()
        clock.set(START)
    }

    @Test
    fun `an accepted message is queued and then sent by the worker`() {
        val sentBefore = counter(DispatchMetrics.SENT_METRIC)
        val payload = payload()

        val id = messageService.receive(Tenant.ROGALAND, payload)

        assertThat(row(id)).isEqualTo(QueueRow("RECEIVED", 0, START))
        assertThat(emailAdapter.sent).isEmpty()

        worker.processDue()

        assertThat(emailAdapter.sent).containsExactly(id to payload)
        assertThat(row(id)).isNull()
        assertThat(counter(DispatchMetrics.SENT_METRIC)).isEqualTo(sentBefore + 1)
    }

    @Test
    fun `a retryable failure is tried again after the backoff`() {
        val retriedBefore = counter(DispatchMetrics.RETRIED_METRIC, "reason" to RetryReason.SERVER_ERROR.value)
        emailAdapter.respondWith(EmailSendOutcome.Retryable(RetryReason.SERVER_ERROR, "HTTP 503"))
        val id = messageService.receive(Tenant.ROGALAND, payload())

        worker.processDue()

        assertThat(row(id)).isEqualTo(QueueRow("RECEIVED", 1, START + Duration.ofMinutes(1)))
        assertThat(counter(DispatchMetrics.RETRIED_METRIC, "reason" to RetryReason.SERVER_ERROR.value))
            .isEqualTo(retriedBefore + 1)

        clock.advance(Duration.ofSeconds(59))
        worker.processDue()
        assertThat(emailAdapter.sent).hasSize(1)

        clock.advance(Duration.ofSeconds(1))
        worker.processDue()
        assertThat(emailAdapter.sent.map { it.first }).containsExactly(id, id)
        assertThat(row(id)).isNull()
    }

    @Test
    fun `retry-after from the provider wins over a shorter backoff`() {
        emailAdapter.respondWith(
            EmailSendOutcome.Retryable(RetryReason.THROTTLED, "HTTP 429", Duration.ofMinutes(10)),
        )
        val id = messageService.receive(Tenant.ROGALAND, payload())

        worker.processDue()

        assertThat(row(id)?.nextAttemptAt).isEqualTo(START + Duration.ofMinutes(10))
    }

    @Test
    fun `the backoff doubles up to the maximum and the message fails when the attempts are used up`() {
        val failedBefore = counter(DispatchMetrics.FAILED_METRIC, "reason" to FailureReason.RETRIES_EXHAUSTED.value)
        repeat(3) { emailAdapter.respondWith(EmailSendOutcome.Retryable(RetryReason.IO, "IOException")) }
        val id = messageService.receive(Tenant.ROGALAND, payload())

        worker.processDue()
        assertThat(row(id)?.nextAttemptAt).isEqualTo(START + Duration.ofMinutes(1))

        clock.advance(Duration.ofMinutes(1))
        worker.processDue()
        assertThat(row(id)?.nextAttemptAt).isEqualTo(clock.instant() + Duration.ofSeconds(90))

        clock.advance(Duration.ofSeconds(90))
        worker.processDue()

        assertThat(emailAdapter.sent).hasSize(3)
        assertThat(row(id)).isNull()
        assertThat(counter(DispatchMetrics.FAILED_METRIC, "reason" to FailureReason.RETRIES_EXHAUSTED.value))
            .isEqualTo(failedBefore + 1)
    }

    @Test
    fun `a permanent failure is not tried again`() {
        val failedBefore = counter(DispatchMetrics.FAILED_METRIC, "reason" to FailureReason.REJECTED.value)
        emailAdapter.respondWith(EmailSendOutcome.Permanent(FailureReason.REJECTED, "HTTP 400"))
        val id = messageService.receive(Tenant.ROGALAND, payload())

        worker.processDue()
        clock.advance(Duration.ofHours(1))
        worker.processDue()

        assertThat(emailAdapter.sent).hasSize(1)
        assertThat(row(id)).isNull()
        assertThat(counter(DispatchMetrics.FAILED_METRIC, "reason" to FailureReason.REJECTED.value))
            .isEqualTo(failedBefore + 1)
    }

    @Test
    fun `a payload that cannot be decrypted fails after the last attempt without stopping the batch`() {
        val failedBefore = counter(DispatchMetrics.FAILED_METRIC, "reason" to FailureReason.RETRIES_EXHAUSTED.value)
        val retriedBefore = counter(DispatchMetrics.RETRIED_METRIC, "reason" to RetryReason.UNEXPECTED.value)
        val corrupted = messageService.receive(Tenant.ROGALAND, payload())
        val intact = messageService.receive(Tenant.ROGALAND, payload())
        jdbcClient
            .sql(
                """
                UPDATE dispatch_queue
                SET payload = set_byte(payload, length(payload) - 1, (get_byte(payload, length(payload) - 1) + 1) % 256)
                WHERE message_id = :id
                """.trimIndent(),
            ).param("id", corrupted.value)
            .update()

        worker.processDue()

        assertThat(emailAdapter.sent.map { it.first }).containsExactly(intact)
        assertThat(row(corrupted)).isEqualTo(QueueRow("RECEIVED", 1, START + Duration.ofMinutes(1)))

        clock.advance(Duration.ofMinutes(1))
        worker.processDue()
        clock.advance(Duration.ofSeconds(90))
        worker.processDue()

        assertThat(row(corrupted)).isNull()
        assertThat(emailAdapter.sent.map { it.first }).containsExactly(intact)
        assertThat(counter(DispatchMetrics.RETRIED_METRIC, "reason" to RetryReason.UNEXPECTED.value))
            .isEqualTo(retriedBefore + 2)
        assertThat(counter(DispatchMetrics.FAILED_METRIC, "reason" to FailureReason.RETRIES_EXHAUSTED.value))
            .isEqualTo(failedBefore + 1)
    }

    @Test
    fun `a message claimed by a worker that died is sent again with the same id when the lease runs out`() {
        val id = messageService.receive(Tenant.ROGALAND, payload())
        val abandoned = repository.claimDue(START, START + Duration.ofMinutes(5), 10).single()

        worker.processDue()
        assertThat(emailAdapter.sent).isEmpty()
        assertThat(row(id)?.status).isEqualTo("PROCESSING")

        clock.advance(Duration.ofMinutes(5))
        worker.processDue()

        assertThat(emailAdapter.sent.map { it.first }).containsExactly(id)
        assertThat(row(id)).isNull()
        assertThat(repository.delete(abandoned)).isFalse()
    }

    @Test
    fun `a late result from an attempt that lost its lease does not touch the newer attempt`() {
        val id = messageService.receive(Tenant.ROGALAND, payload())
        val abandoned = repository.claimDue(START, START + Duration.ofMinutes(5), 10).single()
        clock.advance(Duration.ofMinutes(5))
        val current = repository.claimDue(clock.instant(), clock.instant() + Duration.ofMinutes(5), 10).single()

        assertThat(repository.reschedule(abandoned, clock.instant())).isFalse()
        assertThat(repository.delete(abandoned)).isFalse()
        assertThat(row(id)).isEqualTo(QueueRow("PROCESSING", current.attempts, START))
    }

    @Test
    fun `two workers running at the same time send each message once`() {
        val ids = (1..20).map { messageService.receive(Tenant.ROGALAND, payload()) }
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)

        repeat(2) {
            executor.submit {
                barrier.await()
                repeat(5) { worker.processDue() }
            }
        }
        executor.shutdown()
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue()

        assertThat(emailAdapter.sent.map { it.first }).containsExactlyInAnyOrderElementsOf(ids)
        assertThat(jdbcClient.sql("SELECT count(*) FROM dispatch_queue").query(Long::class.java).single()).isZero()
    }

    @Test
    fun `a rejected message is not queued`() {
        val address = "samme.mottaker@rogfk.no"
        repeat(RECIPIENT_PER_HOUR) { messageService.receive(Tenant.ROGALAND, payload(address)) }

        assertThatThrownBy { messageService.receive(Tenant.ROGALAND, payload(address)) }
            .isInstanceOf(LimitExceededException::class.java)

        assertThat(jdbcClient.sql("SELECT count(*) FROM dispatch_queue").query(Long::class.java).single())
            .isEqualTo(RECIPIENT_PER_HOUR.toLong())
    }

    @Test
    fun `the queue metrics show the size and the age of the oldest message`() {
        messageService.receive(Tenant.ROGALAND, payload())
        clock.advance(Duration.ofMinutes(2))
        messageService.receive(Tenant.ROGALAND, payload())
        clock.advance(Duration.ofMinutes(1))

        queueMetrics.refresh()

        assertThat(meterRegistry.get(DispatchQueueMetrics.SIZE_METRIC).gauge().value()).isEqualTo(2.0)
        assertThat(meterRegistry.get(DispatchQueueMetrics.OLDEST_METRIC).gauge().value()).isEqualTo(180.0)
    }

    private fun payload(to: String = "mottaker-${recipientNumber.incrementAndGet()}@rogfk.no") =
        EmailPayload(templateId = "team/varsel", to = to, subject = "Emne", body = "<p>Innhold</p>", replyTo = null)

    private fun counter(
        name: String,
        vararg tags: Pair<String, String>,
    ): Double =
        meterRegistry
            .find(name)
            .tags("tenant", Tenant.ROGALAND.name, "channel", "EMAIL", *tags.flatMap { it.toList() }.toTypedArray())
            .counter()
            ?.count() ?: 0.0

    private fun row(id: MessageId): QueueRow? =
        jdbcClient
            .sql("SELECT status, attempts, next_attempt_at FROM dispatch_queue WHERE message_id = :id")
            .param("id", id.value)
            .query { rs, _ ->
                QueueRow(
                    rs.getString("status"),
                    rs.getInt("attempts"),
                    rs.getObject("next_attempt_at", OffsetDateTime::class.java).toInstant(),
                )
            }.optional()
            .orElse(null)

    private data class QueueRow(
        val status: String,
        val attempts: Int,
        val nextAttemptAt: Instant,
    )

    private companion object {
        val START: Instant = Instant.parse("2026-10-09T08:00:00Z")
        const val RECIPIENT_PER_HOUR = 10
    }
}
