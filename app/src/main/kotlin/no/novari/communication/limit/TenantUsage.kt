package no.novari.communication.limit

data class TenantUsage(
    val lastHour: Int,
    val lastDay: Int,
) {
    operator fun plus(other: TenantUsage) = TenantUsage(lastHour + other.lastHour, lastDay + other.lastDay)

    companion object {
        val NONE = TenantUsage(0, 0)
    }
}
