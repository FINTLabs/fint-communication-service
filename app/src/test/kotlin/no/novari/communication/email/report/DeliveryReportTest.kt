package no.novari.communication.email.report

import no.novari.communication.IntegrationTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@IntegrationTest
@Import(AzuriteTestConfiguration::class)
@TestPropertySource(
    properties = [
        "communication.email.delivery-reports.enabled=true",
        "communication.email.delivery-reports.poll-interval=1h",
        "communication.email.delivery-reports.queue-metrics-refresh=1h",
        "communication.email.delivery-reports.max-dequeue-count=2",
    ],
)
annotation class DeliveryReportTest
