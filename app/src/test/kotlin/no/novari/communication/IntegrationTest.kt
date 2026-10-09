package no.novari.communication

import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

const val TEST_RECIPIENT_HASHING_KEY = "dGVzdC1ub2trZWwtZm9yLW1vdHRha2VyLWhhc2hpbmc="
const val TEST_DISPATCH_ENCRYPTION_KEY = "dGVzdC1ub2trZWwtZm9yLWtyeXB0ZXJpbmctYXYta28="

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(
    properties = [
        "communication.recipient-hashing.key=$TEST_RECIPIENT_HASHING_KEY",
        "communication.dispatch.encryption-key=$TEST_DISPATCH_ENCRYPTION_KEY",
        "communication.dispatch.poll-interval=1h",
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTestConfiguration::class)
@AutoConfigureMetrics
annotation class IntegrationTest
