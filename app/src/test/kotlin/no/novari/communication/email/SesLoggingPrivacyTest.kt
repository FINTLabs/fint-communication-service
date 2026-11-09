package no.novari.communication.email

import io.micrometer.core.instrument.MeterRegistry
import no.novari.communication.IntegrationTest
import no.novari.communication.MutableClock
import no.novari.communication.message.MessageService
import no.novari.communication.message.MessageStore
import no.novari.communication.message.dispatch.DispatchMetrics
import no.novari.communication.message.dispatch.DispatchProperties
import no.novari.communication.message.dispatch.DispatchQueueRepository
import no.novari.communication.message.dispatch.DispatchWorker
import no.novari.communication.message.dispatch.PayloadCodec
import no.novari.communication.message.domain.EmailPayload
import no.novari.communication.model.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionOperations
import java.io.IOException
import java.time.Duration

@IntegrationTest
@ExtendWith(OutputCaptureExtension::class)
class SesLoggingPrivacyTest {
    @Autowired
    lateinit var messageService: MessageService

    @Autowired
    lateinit var repository: DispatchQueueRepository

    @Autowired
    lateinit var messageStore: MessageStore

    @Autowired
    lateinit var transactions: TransactionOperations

    @Autowired
    lateinit var codec: PayloadCodec

    @Autowired
    lateinit var metrics: DispatchMetrics

    @Autowired
    lateinit var properties: DispatchProperties

    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    @Autowired
    lateinit var jdbcClient: JdbcClient

    @Test
    fun `neither the address, the content nor the keys from SES end up in logs or metrics`(output: CapturedOutput) {
        jdbcClient.sql("DELETE FROM send_usage").update()
        jdbcClient.sql("DELETE FROM dispatch_queue").update()
        val ses = FakeSes()
        ses.onSend(
            FakeSes.error(400, "MessageRejected", "Email address is not verified: $ADDRESS ($SUBJECT)"),
            FakeSes.Response.Failure(IOException("Connection reset ved sending til $ADDRESS")),
            FakeSes.error(429, "TooManyRequestsException", "For mange meldinger til $ADDRESS"),
            FakeSes.accepted(),
        )
        val worker =
            DispatchWorker(
                repository,
                messageStore,
                transactions,
                codec,
                SesEmailAdapter(ses.client(sdkRetries = 1), "no-reply@novari.no", null),
                metrics,
                properties,
                clock,
            )

        repeat(3) {
            messageService.receive(
                Tenant.ROGALAND,
                EmailPayload(templateId = "team/varsel", to = ADDRESS, subject = SUBJECT, body = BODY),
            )
        }
        repeat(3) {
            worker.processDue()
            clock.advance(Duration.ofHours(1))
        }

        assertThat(ses.requests).hasSizeGreaterThanOrEqualTo(4)
        assertThat(output.all)
            .contains("MessageRejected", "TooManyRequestsException")
            .doesNotContainIgnoringCase(ADDRESS)
            .doesNotContain(SUBJECT, BODY, FakeSes.ACCESS_KEY, FakeSes.SECRET_KEY)
        val tagValues = meterRegistry.meters.flatMap { meter -> meter.id.tags.map { it.value } }
        assertThat(tagValues).noneMatch { it.contains(ADDRESS, ignoreCase = true) || it.contains(SUBJECT) }
    }

    private companion object {
        const val ADDRESS = "ses.personvern@rogfk.no"
        const val SUBJECT = "Hemmelig emne 4711"
        const val BODY = "<p>Hemmelig innhold 4711</p>"
    }
}
