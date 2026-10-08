package no.novari.communication.api.validation

data class ValidationError(
    val field: String,
    val message: String,
)
