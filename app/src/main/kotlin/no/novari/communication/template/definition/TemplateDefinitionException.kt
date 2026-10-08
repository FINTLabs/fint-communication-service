package no.novari.communication.template.definition

class TemplateDefinitionException(
    templateId: String,
    reason: String,
    cause: Throwable? = null,
) : RuntimeException("Ugyldig mal '$templateId': $reason", cause)
