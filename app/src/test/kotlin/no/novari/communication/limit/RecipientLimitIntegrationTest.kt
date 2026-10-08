package no.novari.communication.limit

import no.novari.communication.ControllableDispatcher
import no.novari.communication.IntegrationTest
import no.novari.communication.MutableClock
import no.novari.communication.api.MessageController
import no.novari.communication.message.MessageService
import no.novari.communication.message.domain.EmailPayload
import no.novari.communication.model.Tenant
import no.novari.communication.recipient.RecipientHasher
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@IntegrationTest
@ExtendWith(OutputCaptureExtension::class)
class RecipientLimitIntegrationTest {
    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var dispatcher: ControllableDispatcher

    @Autowired
    lateinit var messageService: MessageService

    @Autowired
    lateinit var cleaner: SendUsageCleaner

    @Autowired
    lateinit var hasher: RecipientHasher

    @Autowired
    lateinit var jdbcClient: JdbcClient

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        jdbcClient.sql("DELETE FROM send_usage").update()
        clock.set(START)
        dispatcher.failure = null
    }

    @Test
    fun `the eleventh message within an hour is rejected until the first leaves the window`() {
        repeat(10) { minute ->
            clock.set(START.plus(Duration.ofMinutes(minute.toLong())))
            send().andExpect { status { isAccepted() } }
        }

        clock.set(START.plus(Duration.ofMinutes(10)))
        send().andExpect {
            status { isTooManyRequests() }
            header { string(HttpHeaders.RETRY_AFTER, "3000") }
            jsonPath("$.limit") { value("mottaker") }
        }

        clock.set(START.plus(Duration.ofHours(1)))
        send().andExpect { status { isAccepted() } }
    }

    @Test
    fun `the forty-first message within a day is rejected until the first leaves the window`() {
        repeat(40) { halfHour ->
            clock.set(START.plus(Duration.ofMinutes(30L * halfHour)))
            send().andExpect { status { isAccepted() } }
        }

        clock.set(START.plus(Duration.ofHours(20)))
        send().andExpect {
            status { isTooManyRequests() }
            header { string(HttpHeaders.RETRY_AFTER, "14400") }
            jsonPath("$.limit") { value("mottaker") }
        }

        clock.set(START.plus(Duration.ofDays(1)))
        send().andExpect { status { isAccepted() } }
    }

    @Test
    fun `case, surrounding whitespace and tenant do not give a separate counter`() {
        val variants = listOf("ola@rogfk.no", "OLA@ROGFK.NO", " Ola@RogFK.no ", "ola@Rogfk.No")
        val tenants = listOf(Tenant.ROGALAND, Tenant.AGDER, Tenant.NOVARI)
        repeat(10) { i ->
            sendPayload(variants[i % variants.size], tenants[i % tenants.size])
        }

        assertThat(usageRows()).isEqualTo(10)
        send(to = "OLA@rogfk.no", tenant = Tenant.TROMS).andExpect { status { isTooManyRequests() } }
    }

    @Test
    fun `other recipients are not affected`() {
        repeat(10) { send().andExpect { status { isAccepted() } } }

        send(to = "kari@rogfk.no").andExpect { status { isAccepted() } }
    }

    @Test
    fun `rejected messages are not counted`() {
        repeat(10) { send().andExpect { status { isAccepted() } } }
        clock.set(START.plus(Duration.ofMinutes(30)))
        send().andExpect { status { isTooManyRequests() } }
        send(variables = """{ "saksnummer": "for-lang-verdi" }""").andExpect { status { isBadRequest() } }

        assertThat(usageRows()).isEqualTo(10)
        clock.set(START.plus(Duration.ofHours(1)))
        repeat(10) { send().andExpect { status { isAccepted() } } }
    }

    @Test
    fun `usage is rolled back when dispatch fails`() {
        dispatcher.failure = IllegalStateException("dispatch feilet")

        send().andExpect { status { isInternalServerError() } }

        assertThat(usageRows()).isZero()
    }

    @Test
    fun `parallel requests cannot exceed the limit`() {
        val threads = 20
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        val results =
            try {
                (1..threads)
                    .map {
                        executor.submit(
                            Callable {
                                ready.countDown()
                                go.await()
                                runCatching { sendPayload(ADDRESS, Tenant.ROGALAND) }
                            },
                        )
                    }.also {
                        ready.await()
                        go.countDown()
                    }.map { it.get() }
            } finally {
                executor.shutdown()
            }

        assertThat(results.count { it.isSuccess }).isEqualTo(10)
        assertThat(results.mapNotNull { it.exceptionOrNull() })
            .hasSize(10)
            .allMatch { it is LimitExceededException }
        assertThat(usageRows()).isEqualTo(10)
    }

    @Test
    fun `neither the response nor the log contains the address or its hash`(output: CapturedOutput) {
        repeat(10) { send() }

        send().andExpect {
            status { isTooManyRequests() }
            content { string(not(containsString("ola@rogfk.no"))) }
            content { string(not(containsString(hasher.hash(ADDRESS).value))) }
        }

        assertThat(output.all)
            .contains("Grense overskredet grensetype=mottaker tenant=ROGALAND")
            .doesNotContainIgnoringCase(ADDRESS)
            .doesNotContain(hasher.hash(ADDRESS).value)
    }

    @Test
    fun `the cleaner deletes usage older than a day`() {
        send()
        clock.set(START.plus(Duration.ofHours(23)))
        send()

        val deleted = cleaner.deleteExpired(START.plus(Duration.ofDays(1)))

        assertThat(deleted).isEqualTo(1)
        assertThat(usageRows()).isEqualTo(1)
    }

    private fun send(
        to: String = ADDRESS,
        tenant: Tenant = Tenant.ROGALAND,
        variables: String = """{ "saksnummer": "2026-1" }""",
    ): ResultActionsDsl =
        mockMvc.post(MessageController.MESSAGES_PATH) {
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "tenant": "${tenant.name}",
                  "message": {
                    "channel": "EMAIL",
                    "to": "$to",
                    "templateId": "team/varsel",
                    "variables": $variables,
                    "lists": { "rader": [ { "navn": "ACOS" } ] }
                  }
                }
                """.trimIndent()
        }

    private fun sendPayload(
        to: String,
        tenant: Tenant,
    ) = messageService.receive(
        tenant,
        EmailPayload(templateId = "team/varsel", to = to, subject = "Emne", body = "Innhold"),
    )

    private fun usageRows(): Long = jdbcClient.sql("SELECT count(*) FROM send_usage").query(Long::class.java).single()

    private companion object {
        const val ADDRESS = "ola@rogfk.no"
        val START: Instant = Instant.parse("2026-10-08T08:00:00Z")
    }
}
