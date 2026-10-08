package no.novari.communication.template.definition

data class ListDefinition(
    val maxItems: Int,
    val fields: Map<String, VariableDefinition>,
)
