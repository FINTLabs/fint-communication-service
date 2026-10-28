package no.novari.communication.limit

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(LimitProperties::class)
class LimitConfiguration
