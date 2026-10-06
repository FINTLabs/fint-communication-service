package no.novari.communication.client

import org.springframework.web.client.RestClient
import org.springframework.web.client.support.RestClientAdapter
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.support.WebClientAdapter
import org.springframework.web.service.invoker.HttpServiceProxyFactory
import org.springframework.web.service.invoker.createClient

internal const val MESSAGES_PATH = "/api/v1/messages"

object CommunicationClients {
    @JvmStatic
    fun create(restClient: RestClient): CommunicationClient =
        HttpServiceProxyFactory
            .builderFor(RestClientAdapter.create(restClient))
            .build()
            .createClient<CommunicationClient>()

    @JvmStatic
    fun createReactive(webClient: WebClient): ReactiveCommunicationClient =
        HttpServiceProxyFactory
            .builderFor(WebClientAdapter.create(webClient))
            .build()
            .createClient<ReactiveCommunicationClient>()
}
