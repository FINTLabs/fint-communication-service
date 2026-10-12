package no.novari.communication.limit

import no.novari.communication.model.Tenant
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("communication.limits")
data class LimitProperties(
    val recipient: WindowLimits,
    val tenant: TenantLimits,
    val total: WindowLimits,
) {
    init {
        requireWithinTotal("tenant.default", tenant.default)
        tenant.overrides.forEach { (name, limits) -> requireWithinTotal("tenant.overrides.$name", limits) }
    }

    private fun requireWithinTotal(
        key: String,
        limits: WindowLimits,
    ) {
        require(limits.perHour <= total.perHour) { "$key.per-hour kan ikke være høyere enn total.per-hour" }
        require(limits.perDay <= total.perDay) { "$key.per-day kan ikke være høyere enn total.per-day" }
    }
}

data class TenantLimits(
    val default: WindowLimits,
    val overrides: Map<Tenant, WindowLimits> = emptyMap(),
) {
    fun forTenant(tenant: Tenant): WindowLimits = overrides[tenant] ?: default
}

data class WindowLimits(
    val perHour: Int,
    val perDay: Int,
) {
    init {
        require(perHour > 0) { "per-hour må være større enn 0" }
        require(perDay >= perHour) { "per-day må være minst like stor som per-hour" }
    }
}
