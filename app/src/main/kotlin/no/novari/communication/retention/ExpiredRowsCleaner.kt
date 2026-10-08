package no.novari.communication.retention

import java.time.Instant

interface ExpiredRowsCleaner {
    val name: String

    fun deleteExpired(now: Instant): Int
}
