package no.novari.communication.limit

import no.novari.communication.message.domain.MessageId
import no.novari.communication.model.Tenant
import java.time.Duration

class LimitExceededException(
    val type: LimitType,
    val tenant: Tenant,
    val messageId: MessageId,
    val retryAfter: Duration,
) : RuntimeException("Grense for ${type.value} er overskredet")
