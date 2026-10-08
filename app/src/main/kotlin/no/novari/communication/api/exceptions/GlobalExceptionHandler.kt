package no.novari.communication.api.exceptions

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import no.novari.communication.api.validation.ValidationError
import no.novari.communication.limit.LimitExceededException
import no.novari.communication.template.EmailTemplate
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionSystemException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import tools.jackson.core.JacksonException
import tools.jackson.databind.exc.InvalidTypeIdException
import tools.jackson.databind.exc.MismatchedInputException
import java.sql.SQLException
import java.time.Duration

@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {
    private val log = KotlinLogging.logger {}

    @ExceptionHandler(RequestValidationException::class)
    fun handleRequestValidation(exception: RequestValidationException): ProblemDetail {
        log.warn { "Ugyldig request: ${exception.errors.joinToString { "${it.field} ${it.message}" }}" }
        return badRequest("Requesten inneholder ugyldige felt", exception.errors)
    }

    @ExceptionHandler(LimitExceededException::class)
    fun handleLimitExceeded(exception: LimitExceededException): ResponseEntity<ProblemDetail> {
        log.warn {
            "Grense overskredet grensetype=${exception.type.value} tenant=${exception.tenant} " +
                "id=${exception.messageId.value}"
        }
        val problem =
            ProblemDetail
                .forStatusAndDetail(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Mottakeren har fått for mange meldinger. Prøv igjen senere.",
                ).apply { setProperty("limit", exception.type.value) }
        return ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, retryAfterSeconds(exception.retryAfter).toString())
            .body(problem)
    }

    @ExceptionHandler(
        CannotCreateTransactionException::class,
        DataAccessResourceFailureException::class,
        PessimisticLockingFailureException::class,
    )
    fun handleDatabaseUnavailable(exception: Exception): ProblemDetail {
        log.error(exception) { "Databasen er utilgjengelig" }
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Tjenesten er midlertidig utilgjengelig",
        )
    }

    // Når forbindelsen dør midt i en transaksjon, feiler også rollback, og Spring skjuler da den opprinnelige feilen.
    @ExceptionHandler(TransactionSystemException::class)
    fun handleTransactionFailure(exception: TransactionSystemException): ProblemDetail =
        if (isConnectionFailure(exception.originalException) || isConnectionFailure(exception.cause)) {
            handleDatabaseUnavailable(exception)
        } else {
            handleUnexpected(exception)
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

    private fun isConnectionFailure(exception: Throwable?): Boolean =
        generateSequence(exception) { it.cause }.any {
            it is DataAccessResourceFailureException ||
                (it as? SQLException)?.sqlState?.startsWith(CONNECTION_EXCEPTION_SQL_STATE_CLASS) == true
        }

    private fun retryAfterSeconds(retryAfter: Duration): Long =
        retryAfter.plusNanos(NANOS_PER_SECOND - 1).seconds.coerceAtLeast(1)

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

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val CONNECTION_EXCEPTION_SQL_STATE_CLASS = "08"
    }
}
