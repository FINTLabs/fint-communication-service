package no.novari.communication.api

import no.novari.communication.template.EmailTemplateCatalog
import no.novari.communication.template.definition.EmailTemplateParser

object TestTemplates {
    const val ID = "team/varsel"

    fun catalog(): EmailTemplateCatalog =
        EmailTemplateCatalog(
            listOf(
                EmailTemplateParser().parse(
                    id = ID,
                    definitionYaml =
                        """
                        description: Testmal
                        subject: "Sak {{saksnummer}}"
                        replyTo: no-reply@novari.no
                        variables:
                          saksnummer: { maxLength: 10 }
                        lists:
                          rader:
                            maxItems: 2
                            fields:
                              navn: { maxLength: 10 }
                        """.trimIndent(),
                    bodyHtml = "<p>{{saksnummer}}</p><ul>{{#rader}}<li>{{navn}}</li>{{/rader}}</ul>",
                ),
            ),
        )
}
