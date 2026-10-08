package no.novari.communication.template.loading

import no.novari.communication.template.EmailTemplate
import no.novari.communication.template.EmailTemplateCatalog
import no.novari.communication.template.definition.EmailTemplateParser
import no.novari.communication.template.definition.TemplateDefinitionException
import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.io.support.ResourcePatternResolver

class ClasspathEmailTemplateLoader(
    private val root: String = "templates",
    private val resolver: ResourcePatternResolver = PathMatchingResourcePatternResolver(),
    private val parser: EmailTemplateParser = EmailTemplateParser(),
) {
    private val definitionPath = Regex("""/${Regex.escape(root)}/([^/]+)/email/([^/]+)/template\.yaml$""")

    fun load(): EmailTemplateCatalog =
        EmailTemplateCatalog(
            resolver.getResources("classpath*:$root/*/email/*/template.yaml").map(::loadTemplate),
        )

    private fun loadTemplate(definition: Resource): EmailTemplate {
        val id = templateId(definition)
        val body = definition.createRelative(BODY_FILE)
        if (!body.exists()) {
            throw TemplateDefinitionException(id, "$BODY_FILE mangler")
        }
        return parser.parse(
            id = id,
            definitionYaml = definition.getContentAsString(Charsets.UTF_8),
            bodyHtml = body.getContentAsString(Charsets.UTF_8),
        )
    }

    private fun templateId(definition: Resource): String {
        val location = definition.url.toString()
        val match =
            definitionPath.find(location)
                ?: throw TemplateDefinitionException(location, "uventet plassering")
        return "${match.groupValues[1]}/${match.groupValues[2]}"
    }

    private companion object {
        const val BODY_FILE = "body.html"
    }
}
