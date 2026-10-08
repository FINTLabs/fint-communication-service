package no.novari.communication.template.definition

import com.samskivert.mustache.Mustache
import com.samskivert.mustache.MustacheException
import com.samskivert.mustache.Template
import no.novari.communication.template.EmailTemplate
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.kotlinModule

class EmailTemplateParser {
    private val yamlMapper: YAMLMapper =
        YAMLMapper
            .builder()
            .addModule(kotlinModule())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()
    private val subjectCompiler = Mustache.compiler().escapeHTML(false).strictSections(true)
    private val bodyCompiler = Mustache.compiler().escapeHTML(true).strictSections(true)

    fun parse(
        id: String,
        definitionYaml: String,
        bodyHtml: String,
    ): EmailTemplate {
        if (!EmailTemplate.isValidId(id)) {
            throw TemplateDefinitionException(
                id,
                "id må ha formatet <team>/<mal> med små bokstaver, tall og bindestrek",
            )
        }
        val definition = readDefinition(id, definitionYaml)
        val errors = EmailTemplateStructureChecker(definition, bodyHtml).check()
        if (errors.isNotEmpty()) {
            throw TemplateDefinitionException(id, errors.joinToString("; "))
        }
        return EmailTemplate(
            id = id,
            description = definition.description,
            replyTo = definition.replyTo,
            variables = definition.variables,
            lists = definition.lists,
            subject = compile(id, subjectCompiler, definition.subject),
            body = compile(id, bodyCompiler, bodyHtml),
        )
    }

    private fun readDefinition(
        id: String,
        definitionYaml: String,
    ): EmailTemplateDefinition =
        try {
            yamlMapper.readValue(definitionYaml, EmailTemplateDefinition::class.java)
        } catch (exception: JacksonException) {
            throw TemplateDefinitionException(
                id,
                "template.yaml kan ikke leses: ${exception.originalMessage}",
                exception,
            )
        }

    private fun compile(
        id: String,
        compiler: Mustache.Compiler,
        source: String,
    ): Template =
        try {
            compiler.compile(source)
        } catch (exception: MustacheException) {
            throw TemplateDefinitionException(id, "ugyldig Mustache-syntaks: ${exception.message}", exception)
        }
}
