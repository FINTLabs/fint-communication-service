package no.novari.communication.message

@JvmInline
value class Tenant(
    val orgId: String,
) {
    init {
        require(ORG_ID.matches(orgId)) { "Ugyldig tenant '$orgId': forventer orgId-format, f.eks. rogfk.no" }
    }

    private companion object {
        val ORG_ID = Regex("""^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$""")
    }
}
