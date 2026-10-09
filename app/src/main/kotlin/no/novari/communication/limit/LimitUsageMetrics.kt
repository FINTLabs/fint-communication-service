package no.novari.communication.limit

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import no.novari.communication.model.Tenant
import org.springframework.dao.DataAccessException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class LimitUsageMetrics(
    private val repository: SendUsageRepository,
    private val properties: LimitProperties,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val log = KotlinLogging.logger {}
    private val tenantUsage = usageGauge(TENANT_USAGE_METRIC, "Forbruk delt på grensen per tenant", meterRegistry)
    private val totalUsage = usageGauge(TOTAL_USAGE_METRIC, "Forbruk delt på totalgrensen", meterRegistry)

    @Scheduled(fixedDelayString = "\${communication.limits.usage-refresh}")
    fun refresh() {
        val now = clock.instant()
        val usage =
            try {
                repository.countPerTenantSince(now - DatabaseSendLimiter.HOUR, now - DatabaseSendLimiter.DAY)
            } catch (exception: DataAccessException) {
                log.warn { "Kunne ikke oppdatere utnyttelsen av grensene: ${exception.javaClass.simpleName}" }
                return
            }
        tenantUsage.register(
            Tenant.entries.flatMap { tenant ->
                rows(
                    usage[tenant] ?: TenantUsage.NONE,
                    properties.tenant.forTenant(tenant),
                    Tags.of("tenant", tenant.name),
                )
            },
            true,
        )
        totalUsage.register(
            rows(usage.values.fold(TenantUsage.NONE, TenantUsage::plus), properties.total, Tags.empty()),
            true,
        )
    }

    private fun rows(
        usage: TenantUsage,
        limits: WindowLimits,
        tags: Tags,
    ): List<MultiGauge.Row<*>> =
        listOf(
            MultiGauge.Row.of(tags.and("window", "hour"), usage.lastHour.toDouble() / limits.perHour),
            MultiGauge.Row.of(tags.and("window", "day"), usage.lastDay.toDouble() / limits.perDay),
        )

    private fun usageGauge(
        name: String,
        description: String,
        meterRegistry: MeterRegistry,
    ): MultiGauge =
        MultiGauge
            .builder(name)
            .description(description)
            .baseUnit("ratio")
            .register(meterRegistry)

    companion object {
        const val TENANT_USAGE_METRIC = "communication.limit.tenant.usage"
        const val TOTAL_USAGE_METRIC = "communication.limit.total.usage"
    }
}
