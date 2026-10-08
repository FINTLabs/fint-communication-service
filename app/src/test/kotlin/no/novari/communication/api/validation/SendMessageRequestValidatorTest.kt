package no.novari.communication.api.validation

import no.novari.communication.api.TestTemplates
import no.novari.communication.api.exceptions.RequestValidationException
import no.novari.communication.message.domain.EmailPayload
import no.novari.communication.model.EmailMessage
import no.novari.communication.model.SendMessageRequest
import no.novari.communication.model.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class SendMessageRequestValidatorTest {
    private val validator = SendMessageRequestValidator(TestTemplates.catalog())

    @Test
    fun `a valid request is rendered into an email payload with replyTo from the template`() {
        val validated = validator.validate(validRequest())

        assertThat(validated.tenant).isEqualTo(Tenant.ROGALAND)
        assertThat(validated.toPayload()).isEqualTo(
            EmailPayload(
                templateId = TestTemplates.ID,
                to = "ola@rogfk.no",
                subject = "Sak 2026-1",
                body = "<p>2026-1</p><ul><li>ACOS</li></ul>",
                replyTo = "no-reply@novari.no",
            ),
        )
    }

    @Test
    fun `all errors are reported together`() {
        val request =
            SendMessageRequest(tenant = Tenant.ROGALAND, message = EmailMessage(to = "ola", templateId = "finnes/ikke"))

        assertThat(errorsFor(request)).containsExactly(
            ValidationError("message.to", "må være én gyldig e-postadresse"),
            ValidationError("message.templateId", "ukjent mal 'finnes/ikke'"),
        )
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("invalidEmails")
    fun `an invalid email is rejected with a describing message`(
        email: EmailMessage,
        expected: ValidationError,
    ) {
        assertThat(errorsFor(validRequest().copy(message = email))).containsExactly(expected)
    }

    @Test
    fun `rejected values are never echoed in the messages`() {
        val secret = "12345678901"
        val request =
            validRequest().copy(
                message =
                    validEmail().copy(
                        to = secret,
                        variables = mapOf("saksnummer" to "$secret$secret", "Ola Nordmann $secret" to "x"),
                    ),
            )

        assertThat(errorsFor(request).flatMap { listOf(it.field, it.message) })
            .noneMatch { it.contains(secret) }
    }

    private fun errorsFor(request: SendMessageRequest): List<ValidationError> =
        catchThrowableOfType(RequestValidationException::class.java) { validator.validate(request) }.errors

    companion object {
        private fun validEmail() =
            EmailMessage(
                to = "ola@rogfk.no",
                templateId = TestTemplates.ID,
                variables = mapOf("saksnummer" to "2026-1"),
                lists = mapOf("rader" to listOf(mapOf("navn" to "ACOS"))),
            )

        private fun validRequest() = SendMessageRequest(tenant = Tenant.ROGALAND, message = validEmail())

        private fun withVariable(value: String) = validEmail().copy(variables = mapOf("saksnummer" to value))

        private fun withRows(vararg rows: Map<String, String>) =
            validEmail().copy(
                lists =
                    mapOf("rader" to rows.toList()),
            )

        @JvmStatic
        fun invalidEmails(): List<Arguments> =
            listOf(
                Arguments.of(validEmail().copy(to = " "), ValidationError("message.to", "må være satt")),
                Arguments.of(
                    validEmail().copy(to = "ola@rogfk.no,kari@rogfk.no"),
                    ValidationError("message.to", "må være én gyldig e-postadresse"),
                ),
                Arguments.of(validEmail().copy(templateId = ""), ValidationError("message.templateId", "må være satt")),
                Arguments.of(
                    validEmail().copy(templateId = "Team Varsel"),
                    ValidationError("message.templateId", "har ugyldig format, forventer <team>/<mal>"),
                ),
                Arguments.of(
                    validEmail().copy(variables = emptyMap()),
                    ValidationError("message.variables.saksnummer", "må være satt"),
                ),
                Arguments.of(withVariable("  "), ValidationError("message.variables.saksnummer", "kan ikke være tom")),
                Arguments.of(
                    withVariable("a\nb"),
                    ValidationError(
                        "message.variables.saksnummer",
                        "kan ikke inneholde linjeskift eller andre kontrolltegn",
                    ),
                ),
                Arguments.of(
                    withVariable("a b"),
                    ValidationError(
                        "message.variables.saksnummer",
                        "kan ikke inneholde linjeskift eller andre kontrolltegn",
                    ),
                ),
                Arguments.of(
                    withVariable("12345678901"),
                    ValidationError("message.variables.saksnummer", "kan ikke være lengre enn 10 tegn"),
                ),
                Arguments.of(
                    validEmail().copy(variables = mapOf("saksnummer" to "1", "ekstra" to "x")),
                    ValidationError("message.variables.ekstra", "er ikke definert i malen"),
                ),
                Arguments.of(
                    validEmail().copy(variables = mapOf("saksnummer" to "1", "ola@rogfk.no" to "x")),
                    ValidationError("message.variables", "inneholder 1 navn som ikke er definert i malen"),
                ),
                Arguments.of(
                    validEmail().copy(lists = emptyMap()),
                    ValidationError("message.lists.rader", "må være satt"),
                ),
                Arguments.of(withRows(), ValidationError("message.lists.rader", "må inneholde minst ett element")),
                Arguments.of(
                    withRows(mapOf("navn" to "a"), mapOf("navn" to "b"), mapOf("navn" to "c")),
                    ValidationError("message.lists.rader", "kan ikke inneholde mer enn 2 elementer"),
                ),
                Arguments.of(
                    withRows(mapOf("navn" to "a"), emptyMap()),
                    ValidationError("message.lists.rader[1].navn", "må være satt"),
                ),
                Arguments.of(
                    withRows(mapOf("navn" to "a", "antall" to "1")),
                    ValidationError("message.lists.rader[0].antall", "er ikke definert i malen"),
                ),
                Arguments.of(
                    validEmail().copy(lists = mapOf("rader" to listOf(mapOf("navn" to "a")), "andre" to emptyList())),
                    ValidationError("message.lists.andre", "er ikke definert i malen"),
                ),
            )
    }
}
