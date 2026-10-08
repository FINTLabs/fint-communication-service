package no.novari.communication.model

data class EmailMessage(
    val to: String,
    val templateId: String,
    val variables: Map<String, String> = emptyMap(),
    val lists: Map<String, List<Map<String, String>>> = emptyMap(),
) : Message {
    override fun toString(): String =
        "EmailMessage(to=***, templateId=$templateId, variables=${variables.keys}, lists=${lists.keys})"
}
