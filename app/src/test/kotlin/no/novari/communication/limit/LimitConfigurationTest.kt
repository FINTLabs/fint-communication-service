package no.novari.communication.limit

import no.novari.communication.model.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class LimitConfigurationTest {
    private val contextRunner = ApplicationContextRunner().withUserConfiguration(LimitConfiguration::class.java)

    private val validLimits =
        arrayOf(
            "communication.limits.recipient.per-hour=10",
            "communication.limits.recipient.per-day=40",
            "communication.limits.tenant.default.per-hour=100",
            "communication.limits.tenant.default.per-day=500",
            "communication.limits.total.per-hour=500",
            "communication.limits.total.per-day=2000",
        )

    @Test
    fun `binds the limits`() {
        contextRunner.withPropertyValues(*validLimits).run { context ->
            assertThat(context).hasNotFailed()
            val properties = context.getBean(LimitProperties::class.java)
            assertThat(properties.recipient).isEqualTo(WindowLimits(10, 40))
            assertThat(properties.tenant.default).isEqualTo(WindowLimits(100, 500))
            assertThat(properties.tenant.overrides).isEmpty()
            assertThat(properties.total).isEqualTo(WindowLimits(500, 2000))
        }
    }

    @Test
    fun `an override applies to its tenant and the default to the others`() {
        contextRunner
            .withPropertyValues(
                *validLimits,
                "communication.limits.tenant.overrides.ROGALAND.per-hour=200",
                "communication.limits.tenant.overrides.ROGALAND.per-day=1000",
            ).run { context ->
                assertThat(context).hasNotFailed()
                val tenantLimits = context.getBean(LimitProperties::class.java).tenant
                assertThat(tenantLimits.forTenant(Tenant.ROGALAND)).isEqualTo(WindowLimits(200, 1000))
                assertThat(tenantLimits.forTenant(Tenant.AGDER)).isEqualTo(WindowLimits(100, 500))
            }
    }

    @Test
    fun `fails to start without limits`() {
        contextRunner.run { context -> assertThat(context).hasFailed() }
    }

    @Test
    fun `fails to start without tenant or total limits`() {
        contextRunner
            .withPropertyValues(*validLimits.filterNot { it.contains(".tenant.") }.toTypedArray())
            .run { context -> assertThat(context).hasFailed() }
        contextRunner
            .withPropertyValues(*validLimits.filterNot { it.contains(".total.") }.toTypedArray())
            .run { context -> assertThat(context).hasFailed() }
    }

    @Test
    fun `fails to start when a limit is not positive`() {
        contextRunner
            .withPropertyValues(*validLimits, "communication.limits.recipient.per-hour=0")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(rootCauseMessage(context.startupFailure)).contains("per-hour")
            }
    }

    @Test
    fun `fails to start when the daily limit is below the hourly limit`() {
        contextRunner
            .withPropertyValues(*validLimits, "communication.limits.recipient.per-day=5")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(rootCauseMessage(context.startupFailure)).contains("per-day")
            }
    }

    @Test
    fun `fails to start when an override sets only one window`() {
        contextRunner
            .withPropertyValues(*validLimits, "communication.limits.tenant.overrides.ROGALAND.per-hour=200")
            .run { context -> assertThat(context).hasFailed() }
    }

    @Test
    fun `fails to start when an override names an unknown tenant`() {
        contextRunner
            .withPropertyValues(
                *validLimits,
                "communication.limits.tenant.overrides.UKJENT.per-hour=200",
                "communication.limits.tenant.overrides.UKJENT.per-day=1000",
            ).run { context -> assertThat(context).hasFailed() }
    }

    @Test
    fun `fails to start when the tenant default exceeds the total limit`() {
        contextRunner
            .withPropertyValues(
                *validLimits,
                "communication.limits.tenant.default.per-hour=600",
                "communication.limits.tenant.default.per-day=1000",
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(rootCauseMessage(context.startupFailure)).contains("tenant.default.per-hour")
            }
    }

    @Test
    fun `fails to start when an override exceeds the total limit`() {
        contextRunner
            .withPropertyValues(
                *validLimits,
                "communication.limits.tenant.overrides.NOVARI.per-hour=100",
                "communication.limits.tenant.overrides.NOVARI.per-day=3000",
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(rootCauseMessage(context.startupFailure)).contains("tenant.overrides.NOVARI.per-day")
            }
    }

    private fun rootCauseMessage(failure: Throwable?): String =
        generateSequence(failure) { it.cause }.last().message.orEmpty()
}
