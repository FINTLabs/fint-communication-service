package no.novari.communication.email

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.HttpExecuteResponse
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.SdkHttpResponse
import software.amazon.awssdk.retries.api.BackoffStrategy
import software.amazon.awssdk.services.sesv2.SesV2Client
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

class FakeSes : SdkHttpClient {
    private val responses = ConcurrentLinkedQueue<Response>()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    fun onSend(vararg response: Response) {
        responses += response
    }

    fun client(sdkRetries: Int = 0): SesV2Client =
        SesEmailAdapter
            .clientBuilder(REGION)
            .endpointOverride(URI.create(ENDPOINT))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
            .overrideConfiguration { config ->
                config.retryStrategy {
                    it
                        .maxAttempts(sdkRetries + 1)
                        .backoffStrategy(BackoffStrategy.retryImmediately())
                        .throttlingBackoffStrategy(BackoffStrategy.retryImmediately())
                }
            }.httpClient(this)
            .build()

    override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest =
        object : ExecutableHttpRequest {
            override fun call(): HttpExecuteResponse {
                val body =
                    request
                        .contentStreamProvider()
                        .map { provider -> provider.newStream().use { String(it.readAllBytes()) } }
                        .orElse("")
                requests +=
                    RecordedRequest(request.httpRequest().method().name, request.httpRequest().encodedPath(), body)
                return when (val response = responses.poll() ?: accepted()) {
                    is Response.Failure -> throw response.exception
                    is Response.Http -> response.toExecuteResponse()
                }
            }

            override fun abort() = Unit
        }

    override fun close() = Unit

    data class RecordedRequest(
        val method: String,
        val path: String,
        val body: String,
    )

    sealed interface Response {
        data class Http(
            val status: Int,
            val body: String = "",
            val headers: Map<String, String> = emptyMap(),
        ) : Response {
            fun toExecuteResponse(): HttpExecuteResponse =
                HttpExecuteResponse
                    .builder()
                    .response(
                        SdkHttpResponse
                            .builder()
                            .statusCode(status)
                            .putHeader("Content-Type", "application/json")
                            .apply { headers.forEach { (name, value) -> putHeader(name, value) } }
                            .build(),
                    ).responseBody(AbortableInputStream.create(ByteArrayInputStream(body.toByteArray())))
                    .build()
        }

        data class Failure(
            val exception: IOException,
        ) : Response
    }

    companion object {
        const val REGION = "eu-north-1"
        const val ENDPOINT = "https://email.fake-ses.test"
        const val ACCESS_KEY = "AKIAFAKESESNOKKEL"
        const val SECRET_KEY = "hemmelig-ses-nokkel-for-testing"

        fun accepted(): Response = Response.Http(status = 200, body = """{"MessageId":"0100018f-ses-melding"}""")

        fun error(
            status: Int,
            errorType: String,
            errorMessage: String = "",
        ): Response =
            Response.Http(
                status = status,
                body = """{"message":"$errorMessage"}""",
                headers = mapOf("x-amzn-ErrorType" to errorType),
            )
    }
}
