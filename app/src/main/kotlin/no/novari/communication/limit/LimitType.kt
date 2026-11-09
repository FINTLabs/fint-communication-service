package no.novari.communication.limit

enum class LimitType(
    val value: String,
    val detail: String,
) {
    RECIPIENT("mottaker", "Mottakeren har fått for mange meldinger. Prøv igjen senere."),
    TENANT("tenant", "Tenanten har sendt for mange meldinger. Prøv igjen senere."),
    TOTAL("total", "Tjenesten har sendt for mange meldinger. Prøv igjen senere."),
}
