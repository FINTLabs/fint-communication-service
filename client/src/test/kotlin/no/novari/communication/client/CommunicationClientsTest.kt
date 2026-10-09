package no.novari.communication.client

import com.sun.net.httpserver.HttpServer
import no.novari.communication.model.EmailMessage
import no.novari.communication.model.FailureReason
import no.novari.communication.model.MessageAcceptedResponse
import no.novari.communication.model.MessageStatus
import no.novari.communication.model.MessageStatusResponse
import no.novari.communication.model.SendMessageRequest
import no.novari.communication.model.Tenant
import org.skyscreamer.jsonassert.JSONAssert
import org.springframework.http.client.reactive.JdkClientHttpConnector
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CommunicationClientsTest {
    private val messageId = UUID.fromString("0d6f7e0a-3c1b-4f53-9a35-0a4f8f7f2b11")
    private val request =
        SendMessageRequest(
            tenant = Tenant.ROGALAND,
            message =
                EmailMessage(
                    to = "ola@rogfk.no",
                    templateId = "flyt/integrasjonsfeil",
                    variables = mapOf("antallFeil" to "3"),
                    lists = mapOf("integrasjoner" to listOf(mapOf("navn" to "ACOS", "antall" to "3"))),
                ),
        )
    private val expectedJson =
        """
        {
          "tenant": "ROGALAND",
          "message": {
            "channel": "EMAIL",
            "to": "ola@rogfk.no",
            "templateId": "flyt/integrasjonsfeil",
            "variables": { "antallFeil": "3" },
            "lists": { "integrasjoner": [ { "navn": "ACOS", "antall": "3" } ] }
          }
        }
        """

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private var responseStatus = 202
    private var responseBody = """{"id":"$messageId"}"""
    private var receivedMethod: String? = null
    private var receivedPath: String? = null
    private var receivedContentType: String? = null
    private var receivedBody: String? = null

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        server.createContext("/") { exchange ->
            receivedMethod = exchange.requestMethod
            receivedPath = exchange.requestURI.path
            receivedContentType = exchange.requestHeaders.getFirst("Content-Type")
            receivedBody = exchange.requestBody.readAllBytes().decodeToString()

            val bytes = responseBody.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        baseUrl = "http://localhost:${server.address.port}"
    }

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `blocking client posts request and returns accepted message id`() {
        val client = CommunicationClients.create(RestClient.builder().baseUrl(baseUrl).build())

        val response = client.send(request)

        assertEquals(MessageAcceptedResponse(messageId), response)
        assertReceivedRequest()
    }

    @Test
    fun `reactive client posts request and returns accepted message id`() {
        val client = CommunicationClients.createReactive(webClient())

        val response = client.send(request).block()

        assertEquals(MessageAcceptedResponse(messageId), response)
        assertReceivedRequest()
    }

    @Test
    fun `blocking client throws on client error`() {
        responseStatus = 400
        responseBody = """{"message":"Ugyldig forespørsel"}"""
        val client = CommunicationClients.create(RestClient.builder().baseUrl(baseUrl).build())

        assertFailsWith<HttpClientErrorException.BadRequest> { client.send(request) }
    }

    @Test
    fun `reactive client signals error on client error`() {
        responseStatus = 400
        responseBody = """{"message":"Ugyldig forespørsel"}"""
        val client = CommunicationClients.createReactive(webClient())

        assertFailsWith<WebClientResponseException.BadRequest> { client.send(request).block() }
    }

    @Test
    fun `blocking client gets the status of a message`() {
        responseStatus = 200
        responseBody = sentStatusJson
        val client = CommunicationClients.create(RestClient.builder().baseUrl(baseUrl).build())

        val response = client.getStatus(messageId)

        assertEquals(sentStatus, response)
        assertReceivedStatusRequest()
    }

    @Test
    fun `reactive client gets the status of a message`() {
        responseStatus = 200
        responseBody = sentStatusJson
        val client = CommunicationClients.createReactive(webClient())

        val response = client.getStatus(messageId).block()

        assertEquals(sentStatus, response)
        assertReceivedStatusRequest()
    }

    @Test
    fun `the status of a failed message has the failure reason`() {
        responseStatus = 200
        responseBody =
            """
            {
              "id": "$messageId",
              "status": "FAILED",
              "failureReason": "RETRIES_EXHAUSTED",
              "receivedAt": "2026-10-09T08:00:00Z",
              "updatedAt": "2026-10-09T10:07:00Z"
            }
            """
        val client = CommunicationClients.create(RestClient.builder().baseUrl(baseUrl).build())

        val response = client.getStatus(messageId)

        assertEquals(MessageStatus.FAILED, response.status)
        assertEquals(FailureReason.RETRIES_EXHAUSTED, response.failureReason)
    }

    @Test
    fun `blocking client throws not found for an unknown message`() {
        responseStatus = 404
        responseBody = """{"status":404,"detail":"Meldingen finnes ikke"}"""
        val client = CommunicationClients.create(RestClient.builder().baseUrl(baseUrl).build())

        assertFailsWith<HttpClientErrorException.NotFound> { client.getStatus(messageId) }
    }

    @Test
    fun `reactive client signals not found for an unknown message`() {
        responseStatus = 404
        responseBody = """{"status":404,"detail":"Meldingen finnes ikke"}"""
        val client = CommunicationClients.createReactive(webClient())

        assertFailsWith<WebClientResponseException.NotFound> { client.getStatus(messageId).block() }
    }

    private val sentStatusJson =
        """
        {
          "id": "$messageId",
          "status": "SENT",
          "failureReason": null,
          "receivedAt": "2026-10-09T08:00:00.123456Z",
          "updatedAt": "2026-10-09T08:00:04Z"
        }
        """
    private val sentStatus =
        MessageStatusResponse(
            id = messageId,
            status = MessageStatus.SENT,
            failureReason = null,
            receivedAt = Instant.parse("2026-10-09T08:00:00.123456Z"),
            updatedAt = Instant.parse("2026-10-09T08:00:04Z"),
        )

    private fun assertReceivedStatusRequest() {
        assertEquals("GET", receivedMethod)
        assertEquals("/api/v1/messages/$messageId", receivedPath)
    }

    private fun webClient(): WebClient =
        WebClient
            .builder()
            .clientConnector(JdkClientHttpConnector())
            .baseUrl(baseUrl)
            .build()

    private fun assertReceivedRequest() {
        assertEquals("POST", receivedMethod)
        assertEquals("/api/v1/messages", receivedPath)
        assertEquals("application/json", receivedContentType)
        JSONAssert.assertEquals(expectedJson, receivedBody, true)
    }
}
