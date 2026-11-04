package no.novari.communication.email.report

import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.SqsClientBuilder
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName
import java.time.Duration

class SqsDeliveryReportQueue(
    private val client: SqsClient,
    private val queueUrl: String,
) : DeliveryReportQueue {
    override fun receive(
        maxMessages: Int,
        visibilityTimeout: Duration,
    ): List<QueuedReport> =
        client
            .receiveMessage {
                it
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(maxMessages)
                    .visibilityTimeout(visibilityTimeout.toSeconds().toInt())
                    .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
            }.messages()
            .map { message ->
                QueuedReport(
                    queueMessageId = message.messageId(),
                    receiptHandle = message.receiptHandle(),
                    receiveCount =
                        message
                            .attributes()[MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT]
                            ?.toLongOrNull() ?: 1,
                    body = message.body().toByteArray(Charsets.UTF_8),
                )
            }

    override fun delete(report: QueuedReport) {
        client.deleteMessage { it.queueUrl(queueUrl).receiptHandle(report.receiptHandle) }
    }

    companion object {
        private val API_CALL_TIMEOUT: Duration = Duration.ofSeconds(30)

        fun clientBuilder(region: String): SqsClientBuilder =
            SqsClient
                .builder()
                .region(Region.of(region))
                .overrideConfiguration { it.apiCallTimeout(API_CALL_TIMEOUT) }
    }
}
