package no.novari.communication.email.report

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.azure.AzuriteContainer

@TestConfiguration(proxyBeanMethods = false)
class AzuriteTestConfiguration {
    @Bean
    fun azuriteContainer(): AzuriteContainer = LatestApiAzuriteContainer()

    @Bean
    fun azuriteProperties(azurite: AzuriteContainer): DynamicPropertyRegistrar =
        DynamicPropertyRegistrar { registry ->
            registry.add("communication.email.delivery-reports.connection-string", azurite::getConnectionString)
        }
}

class LatestApiAzuriteContainer : AzuriteContainer("mcr.microsoft.com/azure-storage/azurite:3.37.0") {
    override fun configure() {
        super.configure()
        withCommand(*commandParts, "--skipApiVersionCheck")
    }
}
