package no.novari.communication.template.definition

data class EmailTemplateDefinition(
    val description: String,
    val subject: String,
    val replyTo: String? = null,
    val variables: Map<String, VariableDefinition> = emptyMap(),
    val lists: Map<String, ListDefinition> = emptyMap(),
)
