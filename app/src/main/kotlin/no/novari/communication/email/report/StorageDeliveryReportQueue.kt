package no.novari.communication.email.report

import com.azure.core.http.policy.HttpLogDetailLevel
import com.azure.core.http.policy.HttpLogOptions
import com.azure.core.util.Context
import com.azure.storage.common.policy.RequestRetryOptions
import com.azure.storage.common.policy.RetryPolicyType
import com.azure.storage.queue.QueueClient
import com.azure.storage.queue.QueueClientBuilder
import java.time.Duration

class StorageDeliveryReportQueue(
    private val client: QueueClient,
) : DeliveryReportQueue {
    override fun receive(
        maxMessages: Int,
        visibilityTimeout: Duration,
    ): List<QueuedReport> =
        client
            .receiveMessages(maxMessages, visibilityTimeout, REQUEST_TIMEOUT, Context.NONE)
            .map { QueuedReport(it.messageId, it.popReceipt, it.dequeueCount, it.body.toBytes()) }

    override fun delete(report: QueuedReport) {
        client.deleteMessageWithResponse(report.queueMessageId, report.popReceipt, REQUEST_TIMEOUT, Context.NONE)
    }

    override fun approximateSize(): Long =
        client.getPropertiesWithResponse(REQUEST_TIMEOUT, Context.NONE).value.approximateMessagesCountLong

    companion object {
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)
        private const val MAX_TRIES = 3
        private val TRY_TIMEOUT: Duration = Duration.ofSeconds(10)
        private val RETRY_DELAY: Duration = Duration.ofSeconds(1)
        private val MAX_RETRY_DELAY: Duration = Duration.ofSeconds(5)

        fun clientBuilder(
            connectionString: String,
            queueName: String,
        ): QueueClientBuilder =
            QueueClientBuilder()
                .connectionString(connectionString)
                .queueName(queueName)
                .httpLogOptions(HttpLogOptions().setLogLevel(HttpLogDetailLevel.NONE))
                .retryOptions(
                    RequestRetryOptions(
                        RetryPolicyType.EXPONENTIAL,
                        MAX_TRIES,
                        TRY_TIMEOUT,
                        RETRY_DELAY,
                        MAX_RETRY_DELAY,
                        null,
                    ),
                )
    }
}
