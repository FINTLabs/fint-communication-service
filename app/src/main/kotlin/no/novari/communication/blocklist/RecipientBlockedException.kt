package no.novari.communication.blocklist

import no.novari.communication.message.domain.MessageId
import no.novari.communication.model.Tenant

class RecipientBlockedException(
    val tenant: Tenant,
    val messageId: MessageId,
) : RuntimeException("Mottakeren er blokkert")
