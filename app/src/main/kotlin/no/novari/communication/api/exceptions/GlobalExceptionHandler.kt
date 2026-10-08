package no.novari.communication.api.exceptions

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import no.novari.communication.api.validation.ValidationError
import no.novari.communication.template.EmailTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import tools.jackson.core.JacksonException
import tools.jackson.databind.exc.InvalidTypeIdException
import tools.jackson.databind.exc.MismatchedInputException

@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {
    private val log = KotlinLogging.logger {}

    @ExceptionHandler(RequestValidationException::class)
    fun handleRequestValidation(exception: RequestValidationException): ProblemDetail {
        log.warn { "Ugyldig request: ${exception.errors.joinToString { "${it.field} ${it.message}" }}" }
        return badRequest("Requesten inneholder ugyldige felt", exception.errors)
    }

    override fun handleHttpMessageNotReadable(
        ex: HttpMessageNotReadableException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val jacksonException =
            generateSequence<Throwable>(ex) { it.cause }
                .filterIsInstance<JacksonException>()
                .firstOrNull()
        val error = jacksonException?.let(::invalidField)
        log.warn { "Request-body kunne ikke leses, felt=${error?.field ?: "ukjent"}" }
        val problem =
            if (error == null) {
                badRequest("Request-body er ikke gyldig JSON", emptyList())
            } else {
                badRequest("Requesten inneholder ugyldige felt", listOf(error))
            }
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(exception: Exception): ProblemDetail {
        log.error(exception) { "Uventet feil ved behandling av request" }
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Det oppstod en uventet feil")
    }

    private fun badRequest(
        detail: String,
        errors: List<ValidationError>,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail).apply {
            if (errors.isNotEmpty()) setProperty("errors", errors)
        }

    private fun invalidField(exception: JacksonException): ValidationError? {
        val field = fieldPath(exception) ?: return null
        if (exception is InvalidTypeIdException) {
            val baseType = exception.baseType.rawClass
            val typeProperty = baseType.getAnnotation(JsonTypeInfo::class.java)?.property
            val typeNames =
                baseType
                    .getAnnotation(JsonSubTypes::class.java)
                    ?.value
                    .orEmpty()
                    .map { it.name }
            if (typeProperty != null) {
                return ValidationError("$field.$typeProperty", "må være en av: ${typeNames.joinToString()}")
            }
        }
        val enumType = (exception as? MismatchedInputException)?.targetType?.takeIf { it.isEnum }
        val message =
            enumType?.let { "må være en av: ${it.enumConstants.joinToString()}" } ?: "mangler eller har feil type"
        return ValidationError(field, message)
    }

    private fun fieldPath(exception: JacksonException): String? {
        val path = exception.path.orEmpty()
        if (path.isEmpty()) return null
        return path
            .joinToString("") { reference ->
                when {
                    reference.index >= 0 -> "[${reference.index}]"
                    EmailTemplate.isValidName(reference.propertyName.orEmpty()) -> ".${reference.propertyName}"
                    else -> ".*"
                }
            }.removePrefix(".")
    }
}
