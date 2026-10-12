package no.novari.communication.email.report

class QueuedReport(
    val queueMessageId: String,
    val popReceipt: String,
    val dequeueCount: Long,
    val body: ByteArray,
) {
    override fun toString(): String = "QueuedReport(queueMessageId=$queueMessageId, dequeueCount=$dequeueCount)"
}
