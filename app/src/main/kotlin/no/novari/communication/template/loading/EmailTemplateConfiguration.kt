package no.novari.communication.template.loading

import io.github.oshai.kotlinlogging.KotlinLogging
import no.novari.communication.template.EmailTemplateCatalog
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class EmailTemplateConfiguration {
    private val logger = KotlinLogging.logger {}

    @Bean
    fun emailTemplateCatalog(): EmailTemplateCatalog =
        ClasspathEmailTemplateLoader().load().also { catalog ->
            logger.info { "Lastet ${catalog.ids.size} e-postmaler: ${catalog.ids.sorted()}" }
        }
}
