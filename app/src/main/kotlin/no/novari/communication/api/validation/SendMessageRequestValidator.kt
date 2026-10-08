package no.novari.communication.api.validation

import no.novari.communication.api.exceptions.RequestValidationException
import no.novari.communication.message.domain.EmailAddress
import no.novari.communication.model.EmailMessage
import no.novari.communication.model.SendMessageRequest
import no.novari.communication.model.Tenant
import no.novari.communication.template.EmailTemplate
import no.novari.communication.template.EmailTemplateCatalog
import no.novari.communication.template.definition.ListDefinition
import no.novari.communication.template.definition.VariableDefinition
import org.springframework.stereotype.Component

@Component
class SendMessageRequestValidator(
    private val templates: EmailTemplateCatalog,
) {
    fun validate(request: SendMessageRequest): ValidatedEmailRequest =
        when (val message = request.message) {
            is EmailMessage -> validateEmail(request.tenant, message)
        }

    private fun validateEmail(
        tenant: Tenant,
        email: EmailMessage,
    ): ValidatedEmailRequest {
        val errors = mutableListOf<ValidationError>()
        when {
            email.to.isBlank() -> {
                errors += ValidationError("message.to", MISSING)
            }

            !EmailAddress.isValid(email.to) -> {
                errors +=
                    ValidationError("message.to", "må være én gyldig e-postadresse")
            }
        }
        val template = findTemplate(email.templateId, errors)
        if (template != null) {
            validateValues("message.variables", email.variables, template.variables, errors)
            validateLists(email.lists, template.lists, errors)
        }
        if (errors.isNotEmpty()) {
            throw RequestValidationException(errors)
        }
        return ValidatedEmailRequest(
            tenant = tenant,
            to = email.to,
            template = checkNotNull(template),
            variables = email.variables,
            lists = email.lists,
        )
    }

    private fun findTemplate(
        templateId: String,
        errors: MutableList<ValidationError>,
    ): EmailTemplate? {
        val error =
            when {
                templateId.isBlank() -> MISSING
                !EmailTemplate.isValidId(templateId) -> "har ugyldig format, forventer <team>/<mal>"
                else -> templates.find(templateId)?.let { return it } ?: "ukjent mal '$templateId'"
            }
        errors += ValidationError("message.templateId", error)
        return null
    }

    private fun validateLists(
        lists: Map<String, List<Map<String, String>>>,
        definitions: Map<String, ListDefinition>,
        errors: MutableList<ValidationError>,
    ) {
        definitions.forEach { (name, definition) ->
            val path = "message.lists.$name"
            val items = lists[name]
            when {
                items == null -> {
                    errors += ValidationError(path, MISSING)
                }

                items.isEmpty() -> {
                    errors += ValidationError(path, "må inneholde minst ett element")
                }

                items.size > definition.maxItems -> {
                    errors += ValidationError(path, "kan ikke inneholde mer enn ${definition.maxItems} elementer")
                }

                else -> {
                    items.forEachIndexed { index, item -> validateItem("$path[$index]", item, definition, errors) }
                }
            }
        }
        reportUnknown("message.lists", lists.keys - definitions.keys, errors)
    }

    private fun validateItem(
        path: String,
        item: Map<String, String>?,
        definition: ListDefinition,
        errors: MutableList<ValidationError>,
    ) {
        if (item == null) {
            errors += ValidationError(path, MISSING)
        } else {
            validateValues(path, item, definition.fields, errors)
        }
    }

    private fun validateValues(
        path: String,
        values: Map<String, String>,
        definitions: Map<String, VariableDefinition>,
        errors: MutableList<ValidationError>,
    ) {
        definitions.forEach { (name, definition) ->
            val value = values[name]
            val field = "$path.$name"
            when {
                value == null -> {
                    errors += ValidationError(field, MISSING)
                }

                value.isBlank() -> {
                    errors += ValidationError(field, "kan ikke være tom")
                }

                CONTROL_CHARACTERS.containsMatchIn(value) -> {
                    errors += ValidationError(field, "kan ikke inneholde linjeskift eller andre kontrolltegn")
                }

                value.length > definition.maxLength -> {
                    errors += ValidationError(field, "kan ikke være lengre enn ${definition.maxLength} tegn")
                }
            }
        }
        reportUnknown(path, values.keys - definitions.keys, errors)
    }

    private fun reportUnknown(
        path: String,
        unknown: Set<String>,
        errors: MutableList<ValidationError>,
    ) {
        val (named, unnamed) = unknown.partition(EmailTemplate::isValidName)
        named.sorted().forEach { errors += ValidationError("$path.$it", "er ikke definert i malen") }
        if (unnamed.isNotEmpty()) {
            errors += ValidationError(path, "inneholder ${unnamed.size} navn som ikke er definert i malen")
        }
    }

    private companion object {
        const val MISSING = "må være satt"
        val CONTROL_CHARACTERS = Regex("""[\p{Cc}  ]""")
    }
}
