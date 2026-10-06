package no.novari.communication.client

import com.sun.net.httpserver.HttpServer
import no.novari.communication.model.EmailMessage
import no.novari.communication.model.MessageAcceptedResponse
import no.novari.communication.model.SendMessageRequest
import org.skyscreamer.jsonassert.JSONAssert
import org.springframework.http.client.reactive.JdkClientHttpConnector
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.net.InetSocketAddress
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
            tenant = "rogfk.no",
            email =
                EmailMessage(
                    to = "ola@rogfk.no",
                    subject = "Emne",
                    body = "Innhold",
                    replyTo = "kari@rogfk.no",
                ),
        )
    private val expectedJson =
        """
        {
          "tenant": "rogfk.no",
          "email": {
            "to": "ola@rogfk.no",
            "subject": "Emne",
            "body": "Innhold",
            "replyTo": "kari@rogfk.no"
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
