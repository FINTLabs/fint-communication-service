package no.novari.communication.email.report

import io.micrometer.core.instrument.MeterRegistry
import no.novari.communication.blocklist.BlocklistRepository
import no.novari.communication.message.MessageStore
import no.novari.communication.recipient.RecipientHasher
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.support.TransactionOperations
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

@Configuration
@ConditionalOnBooleanProperty("communication.email.delivery-reports.enabled")
@EnableConfigurationProperties(DeliveryReportProperties::class)
class DeliveryReportConfiguration {
    @Bean
    fun deliveryReportQueue(properties: DeliveryReportProperties): DeliveryReportQueue {
        val connectionString = properties.connectionString
        check(!connectionString.isNullOrBlank()) { "$CONNECTION_STRING_DESCRIPTION mangler" }
        val client =
            try {
                StorageDeliveryReportQueue.clientBuilder(connectionString, properties.queueName).buildClient()
            } catch (_: IllegalArgumentException) {
                throw IllegalStateException("$CONNECTION_STRING_DESCRIPTION er ugyldig")
            }
        return StorageDeliveryReportQueue(client)
    }

    @Bean
    fun deliveryReportParser(
        jsonMapper: JsonMapper,
        recipientHasher: RecipientHasher,
    ): DeliveryReportParser = DeliveryReportParser(jsonMapper, recipientHasher)

    @Bean
    fun deliveryReportMetrics(meterRegistry: MeterRegistry): DeliveryReportMetrics =
        DeliveryReportMetrics(meterRegistry)

    @Bean
    fun deliveryReportQueueMetrics(
        queue: DeliveryReportQueue,
        meterRegistry: MeterRegistry,
    ): DeliveryReportQueueMetrics = DeliveryReportQueueMetrics(queue, meterRegistry)

    @Bean
    fun deliveryReportProcessor(
        messageStore: MessageStore,
        blocklistRepository: BlocklistRepository,
        transactions: TransactionOperations,
        properties: DeliveryReportProperties,
        clock: Clock,
    ): DeliveryReportProcessor =
        DeliveryReportProcessor(messageStore, blocklistRepository, transactions, properties.blockFor, clock)

    @Bean
    fun deliveryReportPoller(
        queue: DeliveryReportQueue,
        parser: DeliveryReportParser,
        processor: DeliveryReportProcessor,
        metrics: DeliveryReportMetrics,
        properties: DeliveryReportProperties,
    ): DeliveryReportPoller = DeliveryReportPoller(queue, parser, processor, metrics, properties)

    companion object {
        const val CONNECTION_STRING_ENVIRONMENT_VARIABLE = "COMMUNICATION_EMAIL_DELIVERY_REPORTS_CONNECTION_STRING"
        private const val CONNECTION_STRING_DESCRIPTION =
            "Connection string for køen med leveringsrapporter " +
                "(communication.email.delivery-reports.connection-string / $CONNECTION_STRING_ENVIRONMENT_VARIABLE)"
    }
}
