package no.novari.communication

import org.springframework.boot.fromApplication
import org.springframework.boot.with

fun main(args: Array<String>) {
    fromApplication<Application>()
        .with(TestcontainersConfiguration::class)
        .run(
            "--communication.recipient-hashing.key=$TEST_RECIPIENT_HASHING_KEY",
            "--communication.dispatch.encryption-key=$TEST_DISPATCH_ENCRYPTION_KEY",
            *args,
        )
}
