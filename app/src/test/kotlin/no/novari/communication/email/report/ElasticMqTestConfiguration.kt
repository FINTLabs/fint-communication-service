package no.novari.communication.email.report

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.sqs.SqsClient
import java.net.URI

@TestConfiguration(proxyBeanMethods = false)
class ElasticMqTestConfiguration {
    @Bean
    fun elasticMqContainer(): ElasticMqContainer = ElasticMqContainer()

    @Bean
    fun sqsTestClient(elasticMq: ElasticMqContainer): SqsClient = sqsClient(elasticMq.endpoint)

    @Bean
    fun deliveryReportQueueUrl(sqsTestClient: SqsClient): DeliveryReportQueueUrl =
        DeliveryReportQueueUrl(sqsTestClient.createQueue { it.queueName(QUEUE_NAME) }.queueUrl())

    @Bean
    @Primary
    fun testDeliveryReportQueue(
        sqsTestClient: SqsClient,
        queueUrl: DeliveryReportQueueUrl,
    ): DeliveryReportQueue = SqsDeliveryReportQueue(sqsTestClient, queueUrl.value)

    companion object {
        const val QUEUE_NAME = "email-delivery-reports"

        fun sqsClient(endpoint: String): SqsClient =
            SqsDeliveryReportQueue
                .clientBuilder("eu-north-1")
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("x", "x")))
                .httpClient(UrlConnectionHttpClient.create())
                .build()
    }
}

data class DeliveryReportQueueUrl(
    val value: String,
)

class ElasticMqContainer :
    GenericContainer<ElasticMqContainer>(DockerImageName.parse("softwaremill/elasticmq-native:1.7.1")) {
    init {
        withExposedPorts(PORT)
    }

    val endpoint: String
        get() = "http://$host:${getMappedPort(PORT)}"

    private companion object {
        const val PORT = 9324
    }
}
