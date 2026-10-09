package no.novari.communication.message

import no.novari.communication.ControllableEmailAdapter
import no.novari.communication.IntegrationTest
import no.novari.communication.MutableClock
import no.novari.communication.message.dispatch.DispatchWorker
import no.novari.communication.message.domain.EmailPayload
import no.novari.communication.message.domain.MessageId
import no.novari.communication.model.MessageStatus
import no.novari.communication.model.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.time.Instant

@IntegrationTest
class MessageCleanerIntegrationTest {
    @Autowired
    lateinit var messageService: MessageService

    @Autowired
    lateinit var worker: DispatchWorker

    @Autowired
    lateinit var cleaner: MessageCleaner

    @Autowired
    lateinit var emailAdapter: ControllableEmailAdapter

    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var jdbcClient: JdbcClient

    @BeforeEach
    fun setUp() {
        jdbcClient.sql("DELETE FROM send_usage").update()
        jdbcClient.sql("DELETE FROM dispatch_queue").update()
        jdbcClient.sql("DELETE FROM message").update()
        emailAdapter.reset()
        clock.set(START)
    }

    @Test
    fun `messages are deleted 60 days after they were received unless they are still queued`() {
        val expired = receive("utlopt@rogfk.no")
        worker.processDue()
        val stillQueued = receive("i-koen@rogfk.no")
        clock.set(START + Duration.ofSeconds(1))
        val atTheLimit = receive("paa-grensen@rogfk.no")
        clock.set(START + Duration.ofDays(1))
        val recent = receive("ny@rogfk.no")

        val deleted = cleaner.deleteExpired(START + Duration.ofDays(60) + Duration.ofSeconds(1))

        assertThat(deleted).isEqualTo(1)
        assertThat(exists(expired)).isFalse()
        assertThat(exists(stillQueued)).isTrue()
        assertThat(exists(atTheLimit)).isTrue()
        assertThat(exists(recent)).isTrue()
    }

    @Test
    fun `a sent message can be looked up until it is deleted`() {
        val id = receive("ola@rogfk.no")
        worker.processDue()

        cleaner.deleteExpired(START + Duration.ofDays(60))
        assertThat(messageService.find(id).status).isEqualTo(MessageStatus.SENT)

        cleaner.deleteExpired(START + Duration.ofDays(60) + Duration.ofMillis(1))
        assertThat(exists(id)).isFalse()
    }

    private fun receive(to: String): MessageId =
        messageService.receive(
            Tenant.ROGALAND,
            EmailPayload(
                templateId = "team/varsel",
                to = to,
                subject = "Emne",
                body = "<p>Innhold</p>",
                replyTo = null,
            ),
        )

    private fun exists(id: MessageId): Boolean =
        jdbcClient
            .sql("SELECT count(*) FROM message WHERE message_id = :id")
            .param("id", id.value)
            .query(Long::class.java)
            .single() == 1L

    private companion object {
        val START: Instant = Instant.parse("2026-10-09T08:00:00Z")
    }
}
