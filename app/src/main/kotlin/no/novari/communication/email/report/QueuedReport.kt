package no.novari.communication.email.report

class QueuedReport(
    val queueMessageId: String,
    val receiptHandle: String,
    val receiveCount: Long,
    val body: ByteArray,
) {
    override fun toString(): String = "QueuedReport(queueMessageId=$queueMessageId, receiveCount=$receiveCount)"
}
