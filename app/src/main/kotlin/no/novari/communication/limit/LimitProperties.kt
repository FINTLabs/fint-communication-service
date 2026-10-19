package no.novari.communication.limit

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("communication.limits")
data class LimitProperties(
    val recipient: WindowLimits,
)

data class WindowLimits(
    val perHour: Int,
    val perDay: Int,
) {
    init {
        require(perHour > 0) { "per-hour må være større enn 0" }
        require(perDay >= perHour) { "per-day må være minst like stor som per-hour" }
    }
}
