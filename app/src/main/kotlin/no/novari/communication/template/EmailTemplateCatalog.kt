package no.novari.communication.template

class EmailTemplateCatalog(
    templates: Collection<EmailTemplate>,
) {
    private val templatesById = templates.associateBy { it.id }

    init {
        val duplicates =
            templates
                .groupingBy { it.id }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        require(duplicates.isEmpty()) { "Maler er definert flere ganger: $duplicates" }
    }

    val ids: Set<String>
        get() = templatesById.keys

    fun find(id: String): EmailTemplate? = templatesById[id]
}
