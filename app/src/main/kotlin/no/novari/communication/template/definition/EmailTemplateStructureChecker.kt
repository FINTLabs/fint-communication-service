package no.novari.communication.template.definition

import no.novari.communication.message.domain.EmailAddress
import no.novari.communication.template.EmailTemplate

internal class EmailTemplateStructureChecker(
    private val definition: EmailTemplateDefinition,
    private val body: String,
) {
    private val errors = mutableListOf<String>()
    private val usedVariables = mutableSetOf<String>()
    private val usedFields = mutableMapOf<String, MutableSet<String>>()

    fun check(): List<String> {
        checkMetadata()
        checkDeclarations()
        checkTags(SUBJECT, definition.subject, allowSections = false)
        checkTags(BODY, body, allowSections = true)
        checkAllDeclarationsUsed()
        return errors
    }

    private fun checkMetadata() {
        if (definition.description.isBlank()) errors += "description må være satt"
        if (definition.subject.isBlank()) errors += "subject må være satt"
        if (definition.subject.contains('\n') || definition.subject.contains('\r')) errors += "subject må være én linje"
        if (body.isBlank()) errors += "$BODY kan ikke være tom"
        definition.replyTo?.let {
            if (!EmailAddress.isValid(it)) errors += "replyTo må være en gyldig e-postadresse"
        }
    }

    private fun checkDeclarations() {
        definition.variables.forEach { (name, variable) -> checkVariable("variabelen '$name'", name, variable) }
        definition.lists.forEach { (name, list) ->
            checkName("listen '$name'", name)
            if (list.maxItems !in 1..MAX_LIST_ITEMS) {
                errors += "listen '$name' må ha maxItems mellom 1 og $MAX_LIST_ITEMS"
            }
            if (list.fields.isEmpty()) errors += "listen '$name' må ha minst ett felt"
            list.fields.forEach { (field, variable) ->
                checkVariable("feltet '$field' i listen '$name'", field, variable)
            }
        }
        definition.variables.keys.intersect(definition.lists.keys).forEach {
            errors += "'$it' er deklarert både som variabel og liste"
        }
    }

    private fun checkVariable(
        description: String,
        name: String,
        variable: VariableDefinition,
    ) {
        checkName(description, name)
        if (variable.maxLength !in 1..MAX_VARIABLE_LENGTH) {
            errors += "$description må ha maxLength mellom 1 og $MAX_VARIABLE_LENGTH"
        }
    }

    private fun checkName(
        description: String,
        name: String,
    ) {
        if (!EmailTemplate.isValidName(name)) {
            errors += "$description har ugyldig navn, forventer bokstaver og tall, f.eks. antallFeil"
        }
    }

    private fun checkTags(
        source: String,
        text: String,
        allowSections: Boolean,
    ) {
        val openSections = ArrayDeque<OpenSection>()
        TAG.findAll(text).forEach { match ->
            if (match.groups[1] != null) {
                errors += "$source: {{{ }}} er ikke tillatt, verdier må escapes"
                return@forEach
            }
            val tag = match.groupValues[2].trim()
            val name = tag.drop(1).trim()
            when (tag.firstOrNull()) {
                '!' -> Unit
                '&' -> errors += "$source: {{& }} er ikke tillatt, verdier må escapes"
                '^' -> errors += "$source: inverterte sections er ikke tillatt"
                '>' -> errors += "$source: partials er ikke tillatt"
                '=' -> errors += "$source: endring av delimitere er ikke tillatt"
                '#' -> openSection(source, name, openSections, allowSections)
                '/' -> closeSection(source, name, openSections)
                else -> useVariable(source, tag, openSections)
            }
        }
        openSections.forEach { errors += "$source: section '${it.name}' er ikke lukket" }
    }

    private fun openSection(
        source: String,
        name: String,
        openSections: ArrayDeque<OpenSection>,
        allowSections: Boolean,
    ) {
        val outer = openSections.lastOrNull()?.name
        val error =
            when {
                !allowSections -> "sections er ikke tillatt"
                outer != null -> "nøstede sections er ikke tillatt ('$name' i '$outer')"
                name !in definition.lists -> "section '$name' er ikke en deklarert liste"
                else -> null
            }
        if (error == null) {
            usedFields.getOrPut(name) { mutableSetOf() }
        } else {
            errors += "$source: $error"
        }
        openSections.addLast(OpenSection(name, accepted = error == null))
    }

    private fun closeSection(
        source: String,
        name: String,
        openSections: ArrayDeque<OpenSection>,
    ) {
        if (openSections.lastOrNull()?.name == name) {
            openSections.removeLast()
        } else {
            errors += "$source: '{{/$name}}' lukker ikke en åpen section"
        }
    }

    private fun useVariable(
        source: String,
        name: String,
        openSections: ArrayDeque<OpenSection>,
    ) {
        if (!EmailTemplate.isValidName(name)) {
            errors += "$source: ugyldig variabelnavn '$name'"
            return
        }
        val openSection = openSections.lastOrNull()
        if (openSection == null) {
            when (name) {
                in definition.variables -> usedVariables += name
                in definition.lists -> errors += "$source: listen '$name' kan bare brukes som section"
                else -> errors += "$source: variabelen '$name' er ikke deklarert"
            }
            return
        }
        if (!openSection.accepted) return
        if (name in definition.lists.getValue(openSection.name).fields) {
            usedFields.getValue(openSection.name) += name
        } else {
            errors += "$source: feltet '$name' er ikke deklarert i listen '${openSection.name}'"
        }
    }

    private fun checkAllDeclarationsUsed() {
        (definition.variables.keys - usedVariables).forEach {
            errors += "variabelen '$it' er deklarert, men ikke brukt"
        }
        definition.lists.forEach { (name, list) ->
            val used = usedFields[name]
            if (used == null) {
                errors += "listen '$name' er deklarert, men ikke brukt"
            } else {
                (list.fields.keys - used).forEach {
                    errors +=
                        "feltet '$it' i listen '$name' er deklarert, men ikke brukt"
                }
            }
        }
    }

    private data class OpenSection(
        val name: String,
        val accepted: Boolean,
    )

    companion object {
        const val MAX_VARIABLE_LENGTH = 1000
        const val MAX_LIST_ITEMS = 100
        private const val SUBJECT = "subject"
        private const val BODY = "body.html"
        private val TAG = Regex("""\{\{\{(.*?)}}}|\{\{(.*?)}}""", RegexOption.DOT_MATCHES_ALL)
    }
}
