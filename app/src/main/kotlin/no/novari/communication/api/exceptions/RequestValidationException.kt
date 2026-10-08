package no.novari.communication.api.exceptions

import no.novari.communication.api.validation.ValidationError

class RequestValidationException(
    val errors: List<ValidationError>,
) : RuntimeException("Ugyldig request: ${errors.map { it.field }}")
