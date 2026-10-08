package no.novari.communication.template.definition

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class EmailTemplateParserTest {
    private val parser = EmailTemplateParser()

    @Test
    fun `a valid template exposes its declarations`() {
        val template = parser.parse("team/mal", VALID_YAML + "replyTo: no-reply@novari.no\n", VALID_BODY)

        assertThat(template.id).isEqualTo("team/mal")
        assertThat(template.description).isEqualTo("Testmal")
        assertThat(template.replyTo).isEqualTo("no-reply@novari.no")
        assertThat(template.variables).containsOnlyKeys("saksnummer")
        assertThat(template.lists).containsOnlyKeys("rader")
        assertThat(template.lists.getValue("rader").fields).containsOnlyKeys("navn")
    }

    @Test
    fun `rendering escapes HTML in the body but not in the subject`() {
        val rendered =
            parser
                .parse("team/mal", VALID_YAML, VALID_BODY)
                .render(
                    variables = mapOf("saksnummer" to "A&B <1>"),
                    lists = mapOf("rader" to listOf(mapOf("navn" to "<script>"), mapOf("navn" to "Kari"))),
                )

        assertThat(rendered.subject).isEqualTo("Sak A&B <1>")
        assertThat(rendered.body)
            .isEqualTo("<p>A&amp;B &lt;1&gt;</p><ul><li>&lt;script&gt;</li><li>Kari</li></ul>")
    }

    @Test
    fun `an id that is not on the form team slash name is rejected`() {
        assertThatThrownBy { parser.parse("Team/Mal", VALID_YAML, VALID_BODY) }
            .isInstanceOf(TemplateDefinitionException::class.java)
            .hasMessageContaining("<team>/<mal>")
    }

    @ParameterizedTest(name = "{2}")
    @MethodSource("invalidTemplates")
    fun `an invalid template is rejected with a describing message`(
        yaml: String,
        body: String,
        expectedMessage: String,
    ) {
        assertThatThrownBy { parser.parse("team/mal", yaml, body) }
            .isInstanceOf(TemplateDefinitionException::class.java)
            .hasMessageStartingWith("Ugyldig mal 'team/mal'")
            .hasMessageContaining(expectedMessage)
    }

    companion object {
        private val VALID_YAML =
            """
            description: Testmal
            subject: "Sak {{saksnummer}}"
            variables:
              saksnummer: { maxLength: 32 }
            lists:
              rader:
                maxItems: 10
                fields:
                  navn: { maxLength: 50 }
            """.trimIndent() + "\n"
        private const val VALID_BODY = "<p>{{saksnummer}}</p><ul>{{#rader}}<li>{{navn}}</li>{{/rader}}</ul>"

        private fun body(extra: String) = "$VALID_BODY$extra"

        @JvmStatic
        fun invalidTemplates(): List<Arguments> =
            listOf(
                Arguments.of(VALID_YAML, body("{{{saksnummer}}}"), "{{{ }}} er ikke tillatt"),
                Arguments.of(VALID_YAML, body("{{&saksnummer}}"), "{{& }} er ikke tillatt"),
                Arguments.of(VALID_YAML, body("{{^rader}}Ingen{{/rader}}"), "inverterte sections er ikke tillatt"),
                Arguments.of(VALID_YAML, body("{{> footer}}"), "partials er ikke tillatt"),
                Arguments.of(VALID_YAML, body("{{=<% %>=}}"), "endring av delimitere er ikke tillatt"),
                Arguments.of(
                    VALID_YAML,
                    body("{{#saksnummer}}x{{/saksnummer}}"),
                    "section 'saksnummer' er ikke en deklarert liste",
                ),
                Arguments.of(
                    VALID_YAML,
                    "{{saksnummer}}{{#rader}}{{#rader}}{{navn}}{{/rader}}{{/rader}}",
                    "nøstede sections er ikke tillatt",
                ),
                Arguments.of(VALID_YAML, "{{saksnummer}}{{#rader}}{{navn}}", "section 'rader' er ikke lukket"),
                Arguments.of(VALID_YAML, body("{{/rader}}"), "'{{/rader}}' lukker ikke en åpen section"),
                Arguments.of(VALID_YAML, body("{{ukjent}}"), "variabelen 'ukjent' er ikke deklarert"),
                Arguments.of(
                    VALID_YAML,
                    body("{{#rader}}{{saksnummer}}{{/rader}}"),
                    "feltet 'saksnummer' er ikke deklarert i listen 'rader'",
                ),
                Arguments.of(VALID_YAML, body("{{rader}}"), "listen 'rader' kan bare brukes som section"),
                Arguments.of(VALID_YAML, body("{{saksnummer.lengde}}"), "ugyldig variabelnavn"),
                Arguments.of(VALID_YAML, "  ", "body.html kan ikke være tom"),
                Arguments.of(
                    VALID_YAML.replace("Sak {{saksnummer}}", "{{#rader}}{{navn}}{{/rader}}"),
                    VALID_BODY,
                    "subject: sections er ikke tillatt",
                ),
                Arguments.of(
                    VALID_YAML.replace("Sak {{saksnummer}}", "Sak\\n{{saksnummer}}"),
                    VALID_BODY,
                    "subject må være én linje",
                ),
                Arguments.of(
                    VALID_YAML.replace("description: Testmal", "description: ' '"),
                    VALID_BODY,
                    "description må være satt",
                ),
                Arguments.of(
                    VALID_YAML.replace("maxLength: 32", "maxLength: 1001"),
                    VALID_BODY,
                    "maxLength mellom 1 og 1000",
                ),
                Arguments.of(
                    VALID_YAML.replace("maxLength: 32", "maxLength: 0"),
                    VALID_BODY,
                    "maxLength mellom 1 og 1000",
                ),
                Arguments.of(
                    VALID_YAML.replace("maxItems: 10", "maxItems: 101"),
                    VALID_BODY,
                    "maxItems mellom 1 og 100",
                ),
                Arguments.of(
                    VALID_YAML.replace("saksnummer: {", "saks-nummer: {"),
                    VALID_BODY,
                    "variabelen 'saks-nummer' har ugyldig navn",
                ),
                Arguments.of(
                    VALID_YAML + "replyTo: ikke-en-adresse\n",
                    VALID_BODY,
                    "replyTo må være en gyldig e-postadresse",
                ),
                Arguments.of(VALID_YAML + "ukjentFelt: verdi\n", VALID_BODY, "template.yaml kan ikke leses"),
                Arguments.of("subject: Mangler description\n", VALID_BODY, "template.yaml kan ikke leses"),
                Arguments.of(
                    VALID_YAML.replace("variables:", "variables:\n  ubrukt: { maxLength: 5 }"),
                    VALID_BODY,
                    "variabelen 'ubrukt' er deklarert, men ikke brukt",
                ),
                Arguments.of(
                    VALID_YAML.replace(
                        "navn: { maxLength: 50 }",
                        "navn: { maxLength: 50 }\n      ubrukt: { maxLength: 5 }",
                    ),
                    VALID_BODY,
                    "feltet 'ubrukt' i listen 'rader' er deklarert, men ikke brukt",
                ),
                Arguments.of(
                    VALID_YAML.replace("lists:", "lists:\n  tom:\n    maxItems: 5\n    fields: {}"),
                    VALID_BODY,
                    "listen 'tom' må ha minst ett felt",
                ),
                Arguments.of(
                    VALID_YAML.replace(
                        "lists:",
                        "lists:\n  saksnummer:\n    maxItems: 5\n    fields:\n      navn: { maxLength: 5 }",
                    ),
                    VALID_BODY,
                    "'saksnummer' er deklarert både som variabel og liste",
                ),
            )
    }
}
