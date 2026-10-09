package no.novari.communication.email

import com.azure.communication.email.EmailClient
import com.azure.core.http.HttpClient
import com.azure.core.http.HttpHeaderName
import com.azure.core.http.HttpHeaders
import com.azure.core.http.HttpMethod
import com.azure.core.http.HttpRequest
import com.azure.core.http.HttpResponse
import com.azure.core.http.policy.FixedDelayOptions
import com.azure.core.http.policy.RetryOptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

class FakeAcs : HttpClient {
    private val sendResponses = ConcurrentLinkedQueue<Response>()
    private val pollResponses = ConcurrentLinkedQueue<Response>()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    fun onSend(vararg responses: Response) {
        sendResponses += responses
    }

    fun onPoll(vararg responses: Response) {
        pollResponses += responses
    }

    fun client(sdkRetries: Int = 0): EmailClient =
        AcsEmailAdapter
            .clientBuilder(CONNECTION_STRING)
            .httpClient(this)
            .retryOptions(RetryOptions(FixedDelayOptions(sdkRetries, Duration.ofMillis(1))))
            .buildClient()

    val sendRequests: List<RecordedRequest>
        get() = requests.filter { it.method == HttpMethod.POST }

    override fun send(request: HttpRequest): Mono<HttpResponse> {
        val body = request.bodyAsBinaryData?.toString().orEmpty()
        requests += RecordedRequest(request.httpMethod, request.url.toString(), request.headers, body)
        val response =
            if (request.httpMethod == HttpMethod.POST) {
                sendResponses.poll() ?: accepted()
            } else {
                pollResponses.poll() ?: status("Succeeded")
            }
        return when (response) {
            is Response.Failure -> Mono.error(response.exception)
            is Response.Http -> Mono.just(FakeResponse(request, response))
        }
    }

    data class RecordedRequest(
        val method: HttpMethod,
        val url: String,
        val headers: HttpHeaders,
        val body: String,
    )

    sealed interface Response {
        data class Http(
            val status: Int,
            val body: String = "",
            val headers: Map<String, String> = emptyMap(),
        ) : Response

        data class Failure(
            val exception: Throwable,
        ) : Response
    }

    private class FakeResponse(
        request: HttpRequest,
        private val response: Response.Http,
    ) : HttpResponse(request) {
        private val headers =
            HttpHeaders().apply {
                set(HttpHeaderName.CONTENT_TYPE, "application/json")
                response.headers.forEach { (name, value) -> set(HttpHeaderName.fromString(name), value) }
            }

        override fun getStatusCode(): Int = response.status

        @Deprecated("Deprecated in azure-core")
        override fun getHeaderValue(name: String): String? = headers.getValue(HttpHeaderName.fromString(name))

        override fun getHeaders(): HttpHeaders = headers

        override fun getBody(): Flux<ByteBuffer> = Flux.just(ByteBuffer.wrap(response.body.toByteArray()))

        override fun getBodyAsByteArray(): Mono<ByteArray> = Mono.just(response.body.toByteArray())

        override fun getBodyAsString(): Mono<String> = Mono.just(response.body)

        override fun getBodyAsString(charset: Charset): Mono<String> = Mono.just(response.body)
    }

    companion object {
        const val ENDPOINT = "https://test.communication.azure.com"
        val ACCESS_KEY: String = Base64.getEncoder().encodeToString("hemmelig-acs-nokkel-for-testing!".toByteArray())
        val CONNECTION_STRING = "endpoint=$ENDPOINT/;accesskey=$ACCESS_KEY"
        private const val OPERATION_ID = "0d6f7e0a-3c1b-4f53-9a35-0a4f8f7f2b11"

        fun accepted(): Response =
            Response.Http(
                status = 202,
                body = """{"id":"$OPERATION_ID","status":"Running"}""",
                headers =
                    mapOf(
                        "Operation-Location" to "$ENDPOINT/emails/operations/$OPERATION_ID?api-version=2023-03-31",
                    ),
            )

        fun status(
            status: String,
            errorCode: String? = null,
            errorMessage: String = "",
        ): Response =
            Response.Http(
                status = 200,
                body =
                    if (errorCode == null) {
                        """{"id":"$OPERATION_ID","status":"$status"}"""
                    } else {
                        """{"id":"$OPERATION_ID","status":"$status","error":{"code":"$errorCode","message":"$errorMessage"}}"""
                    },
            )

        fun error(
            status: Int,
            errorCode: String,
            errorMessage: String = "",
            headers: Map<String, String> = emptyMap(),
        ): Response =
            Response.Http(
                status = status,
                body = """{"error":{"code":"$errorCode","message":"$errorMessage"}}""",
                headers = headers + ("x-ms-error-code" to errorCode),
            )
    }
}
