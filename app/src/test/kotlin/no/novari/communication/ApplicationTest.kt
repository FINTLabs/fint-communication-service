package no.novari.communication

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@IntegrationTest
@AutoConfigureMockMvc
class ApplicationTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `health endpoint reports up`() {
        mockMvc.get("/actuator/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
        }
    }

    @Test
    fun `liveness probe reports up`() {
        mockMvc.get("/actuator/health/liveness").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
        }
    }

    @Test
    fun `readiness probe reports up`() {
        mockMvc.get("/actuator/health/readiness").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
        }
    }

    @Test
    fun `metrics endpoint lists meters`() {
        mockMvc.get("/actuator/metrics").andExpect {
            status { isOk() }
            jsonPath("$.names") { isNotEmpty() }
        }
    }

    @Test
    fun `prometheus endpoint exposes meters in prometheus format`() {
        mockMvc.get("/actuator/prometheus").andExpect {
            status { isOk() }
            content { string(containsString("# TYPE jvm_memory_used_bytes gauge")) }
        }
    }
}
