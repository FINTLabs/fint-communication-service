package no.novari.communication

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

const val TEST_RECIPIENT_HASHING_KEY = "dGVzdC1ub2trZWwtZm9yLW1vdHRha2VyLWhhc2hpbmc="

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(properties = ["communication.recipient-hashing.key=$TEST_RECIPIENT_HASHING_KEY"])
@Import(TestcontainersConfiguration::class)
annotation class IntegrationTest
