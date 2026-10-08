package no.novari.communication.template

import com.samskivert.mustache.Template
import no.novari.communication.template.definition.ListDefinition
import no.novari.communication.template.definition.VariableDefinition

class EmailTemplate internal constructor(
    val id: String,
    val description: String,
    val replyTo: String?,
    val variables: Map<String, VariableDefinition>,
    val lists: Map<String, ListDefinition>,
    private val subject: Template,
    private val body: Template,
) {
    fun render(
        variables: Map<String, String>,
        lists: Map<String, List<Map<String, String>>>,
    ): RenderedEmail {
        val context: Map<String, Any> = variables + lists
        return RenderedEmail(subject = subject.execute(context), body = body.execute(context))
    }

    override fun toString(): String = "EmailTemplate(id=$id)"

    companion object {
        const val MAX_ID_LENGTH = 128
        private val ID = Regex("""^[a-z0-9]+(-[a-z0-9]+)*/[a-z0-9]+(-[a-z0-9]+)*$""")
        private val NAME = Regex("""^[a-zA-Z][a-zA-Z0-9]{0,63}$""")

        fun isValidId(id: String): Boolean = id.length <= MAX_ID_LENGTH && ID.matches(id)

        fun isValidName(name: String): Boolean = NAME.matches(name)
    }
}
