package no.novari.communication.email

import com.azure.core.http.policy.RequestRetryCondition
import com.azure.core.http.policy.RetryStrategy

/**
 * ACS teller også avviste forespørsler mot kvoten, så SDK-ets egne forsøk ved 429 forlenger strupingen.
 * Køen venter i stedet på `Retry-After`.
 */
class ThrottlingNotRetried(
    private val delegate: RetryStrategy,
) : RetryStrategy by delegate {
    override fun shouldRetryCondition(requestRetryCondition: RequestRetryCondition): Boolean =
        requestRetryCondition.response?.statusCode != RetryStrategy.HTTP_STATUS_TOO_MANY_REQUESTS &&
            delegate.shouldRetryCondition(requestRetryCondition)
}
