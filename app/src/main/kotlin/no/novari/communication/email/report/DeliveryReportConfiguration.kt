package no.novari.communication.email.report

import io.micrometer.core.instrument.MeterRegistry
import no.novari.communication.message.MessageStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.support.TransactionOperations
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

@Configuration
@ConditionalOnBooleanProperty("communication.email.delivery-reports.enabled")
@EnableConfigurationProperties(DeliveryReportProperties::class)
class DeliveryReportConfiguration {
    @Bean
    fun deliveryReportQueue(properties: DeliveryReportProperties): DeliveryReportQueue {
        val queueUrl = properties.queueUrl
        check(!queueUrl.isNullOrBlank()) { "URL-en til køen med leveringsrapporter ($QUEUE_URL_PROPERTY) mangler" }
        val client =
            SqsDeliveryReportQueue
                .clientBuilder(properties.region)
                .httpClient(UrlConnectionHttpClient.create())
                .build()
        return SqsDeliveryReportQueue(client, queueUrl)
    }

    @Bean
    fun deliveryReportParser(jsonMapper: JsonMapper): DeliveryReportParser = DeliveryReportParser(jsonMapper)

    @Bean
    fun deliveryReportMetrics(meterRegistry: MeterRegistry): DeliveryReportMetrics =
        DeliveryReportMetrics(meterRegistry)

    @Bean
    fun deliveryReportProcessor(
        messageStore: MessageStore,
        transactions: TransactionOperations,
        clock: Clock,
    ): DeliveryReportProcessor = DeliveryReportProcessor(messageStore, transactions, clock)

    @Bean
    fun deliveryReportPoller(
        queue: DeliveryReportQueue,
        parser: DeliveryReportParser,
        processor: DeliveryReportProcessor,
        metrics: DeliveryReportMetrics,
    ): DeliveryReportPoller = DeliveryReportPoller(queue, parser, processor, metrics)

    private companion object {
        const val QUEUE_URL_PROPERTY = "communication.email.delivery-reports.queue-url"
    }
}
