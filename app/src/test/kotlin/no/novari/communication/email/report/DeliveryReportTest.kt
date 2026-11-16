package no.novari.communication.email.report

import no.novari.communication.IntegrationTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@IntegrationTest
@Import(ElasticMqTestConfiguration::class)
@TestPropertySource(
    properties = [
        "communication.email.delivery-reports.enabled=true",
        "communication.email.delivery-reports.queue-url=http://localhost/000000000000/brukes-ikke",
        "communication.email.delivery-reports.poll-interval=1h",
    ],
)
annotation class DeliveryReportTest
