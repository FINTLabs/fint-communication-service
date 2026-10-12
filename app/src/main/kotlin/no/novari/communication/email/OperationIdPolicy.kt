package no.novari.communication.email

import com.azure.core.http.HttpHeaderName
import com.azure.core.http.HttpMethod
import com.azure.core.http.HttpPipelineCallContext
import com.azure.core.http.policy.HttpPipelineSyncPolicy
import com.azure.core.util.Context
import no.novari.communication.message.domain.MessageId
import java.util.UUID

class OperationIdPolicy : HttpPipelineSyncPolicy() {
    override fun beforeSendingRequest(context: HttpPipelineCallContext) {
        val operationId = context.getData(CONTEXT_KEY).orElse(null) as? UUID ?: return
        if (context.httpRequest.httpMethod == HttpMethod.POST) {
            context.httpRequest.setHeader(OPERATION_ID_HEADER, operationId.toString())
        }
    }

    companion object {
        val OPERATION_ID_HEADER: HttpHeaderName = HttpHeaderName.fromString("Operation-Id")
        private const val CONTEXT_KEY = "communication.message-id"

        fun context(id: MessageId): Context = Context(CONTEXT_KEY, id.value)
    }
}
