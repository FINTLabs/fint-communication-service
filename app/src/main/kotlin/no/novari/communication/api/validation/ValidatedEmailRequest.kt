package no.novari.communication.api.validation

import no.novari.communication.message.domain.EmailPayload
import no.novari.communication.model.Tenant
import no.novari.communication.template.EmailTemplate

class ValidatedEmailRequest(
    val tenant: Tenant,
    private val to: String,
    private val template: EmailTemplate,
    private val variables: Map<String, String>,
    private val lists: Map<String, List<Map<String, String>>>,
) {
    fun toPayload(): EmailPayload {
        val rendered = template.render(variables, lists)
        return EmailPayload(
            templateId = template.id,
            to = to,
            subject = rendered.subject,
            body = rendered.body,
            replyTo = template.replyTo,
        )
    }
}
