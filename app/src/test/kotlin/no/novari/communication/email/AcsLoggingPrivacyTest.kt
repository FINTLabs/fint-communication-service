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
class AcsLoggingPrivacyTest {
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
    fun `neither the address, the content nor the connection string from ACS ends up in logs or metrics`(
        output: CapturedOutput,
    ) {
        jdbcClient.sql("DELETE FROM send_usage").update()
        jdbcClient.sql("DELETE FROM dispatch_queue").update()
        val acs = FakeAcs()
        acs.onSend(
            FakeAcs.error(400, "InvalidRecipient", "Mottakeren $ADDRESS er ugyldig: $SUBJECT"),
            FakeAcs.Response.Failure(IOException("Connection reset ved sending til $ADDRESS")),
            FakeAcs.error(429, "TooManyRequests", "For mange meldinger til $ADDRESS"),
            FakeAcs.accepted(),
        )
        acs.onPoll(FakeAcs.status("Failed", "EmailDroppedAllRecipientsSuppressed", "$ADDRESS er undertrykt"))
        val worker =
            DispatchWorker(
                repository,
                messageStore,
                transactions,
                codec,
                AcsEmailAdapter(acs.client(sdkRetries = 1), "no-reply@novari.no", Duration.ofSeconds(10)),
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

        assertThat(acs.sendRequests).hasSizeGreaterThanOrEqualTo(4)
        assertThat(output.all)
            .contains("InvalidRecipient", "EmailDroppedAllRecipientsSuppressed")
            .doesNotContainIgnoringCase(ADDRESS)
            .doesNotContain(SUBJECT, BODY, FakeAcs.ACCESS_KEY, FakeAcs.ENDPOINT)
        val tagValues = meterRegistry.meters.flatMap { meter -> meter.id.tags.map { it.value } }
        assertThat(tagValues).noneMatch { it.contains(ADDRESS, ignoreCase = true) || it.contains(SUBJECT) }
    }

    private companion object {
        const val ADDRESS = "acs.personvern@rogfk.no"
        const val SUBJECT = "Hemmelig emne 4711"
        const val BODY = "<p>Hemmelig innhold 4711</p>"
    }
}
