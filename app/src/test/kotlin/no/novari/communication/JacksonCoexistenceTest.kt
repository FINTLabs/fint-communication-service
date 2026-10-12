package no.novari.communication

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter

@IntegrationTest
class JacksonCoexistenceTest {
    @Autowired
    lateinit var context: ApplicationContext

    @Autowired
    lateinit var handlerAdapter: RequestMappingHandlerAdapter

    @Test
    fun `jackson 2 from the azure sdk is on the classpath`() {
        assertThat(ObjectMapper::class.java.`package`.implementationVersion).startsWith("2.")
    }

    @Test
    fun `spring mvc reads and writes json with jackson 3 only`() {
        val jsonConverters =
            handlerAdapter.messageConverters.filter { converter ->
                converter.supportedMediaTypes.any { it.subtype == "json" }
            }

        assertThat(jsonConverters).isNotEmpty().allMatch { it is JacksonJsonHttpMessageConverter }
    }

    @Test
    fun `no jackson 2 object mapper is registered as a bean`() {
        assertThat(context.getBeanNamesForType(ObjectMapper::class.java)).isEmpty()
    }
}
