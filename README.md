# FINT Communication Service

Felles kommunikasjonstjeneste for Novari-plattformen. Applikasjoner og integrasjonsplattformen
sender meldinger hit, og tjenesten står for validering, layout og utsending via ekstern
leverandør. E-post er første kanal; SMS, Slack og webhooks skal kunne legges til uten vesentlig
endring i grunnarkitekturen.

Tjenesten er én multi-tenant deployment i namespace `fintlabs-no`, kun tilgjengelig internt i
clusteret. Tenant er fylket meldingen sendes på vegne av.

## Status

Grunnstruktur: Spring Boot 4-applikasjon med health-endepunkter, bygg og deploy til beta.
REST API, layout, leverandøradapter og autentisering kommer i egne oppgaver under
[FFS-1865](https://novari-iks.atlassian.net/browse/FFS-1865).

## Moduler

| Modul    | Innhold                                                        | Artifact                              |
|----------|----------------------------------------------------------------|---------------------------------------|
| `app`    | Spring Boot-tjenesten (API og motor). Deployes, releases ikke. | –                                     |
| `model`  | API-kontrakten (request/response), uten avhengigheter.         | `no.novari:fint-communication-model`  |
| `client` | HTTP-klient for APIet, blokkerende og reactive.                | `no.novari:fint-communication-client` |

`model` og `client` er kompilert for Java 21 og fungerer med både Spring Boot 3 og 4.
Kontrakten for `POST /api/v1/messages` er et utkast frem til endepunktet implementeres i
[FFS-1967](https://novari-iks.atlassian.net/browse/FFS-1967).

## Endepunkter

| Endepunkt                     | Bruk                    |
|-------------------------------|-------------------------|
| `/actuator/health`            | Startup-probe           |
| `/actuator/health/liveness`   | Liveness-probe          |
| `/actuator/health/readiness`  | Readiness-probe         |

## Lokal utvikling

Krever Java 25.

```bash
./gradlew check
```

`check` kjører tester og ktlint i alle moduler. Klienten testes mot både Spring Framework 6.2
med Jackson 2 (`:client:test`) og Spring Framework 7 med Jackson 3 (`:client:testSpring7`).

```bash
./gradlew :app:bootRun
```

## Bruk av klienten

```kotlin
repositories {
    maven("https://repo.fintlabs.no/releases")
}

dependencies {
    implementation("no.novari:fint-communication-client:<versjon>")
}
```

Klienten tar inn en ferdig konfigurert `RestClient` eller `WebClient`. Base-URL, autentisering og
Jackson-oppsett styres av applikasjonen som bruker den.

```kotlin
val client = CommunicationClients.create(restClientBuilder.baseUrl(baseUrl).build())

val response =
    client.send(
        SendMessageRequest(
            tenant = "rogfk.no",
            email = EmailMessage(to = "ola@rogfk.no", subject = "Emne", body = "Innhold"),
        ),
    )
```

For reactive applikasjoner: `CommunicationClients.createReactive(webClient)`, som returnerer
`Mono<MessageAcceptedResponse>`.

## Release av bibliotekene

Opprett en GitHub-release med tag `vX.Y.Z`. `Publish to Reposilite`-workflowen publiserer da
`model` og `client` med versjon `X.Y.Z`.

## Deploy

| Miljø | Cluster                    | Namespace     | Overlay                               |
|-------|----------------------------|---------------|---------------------------------------|
| beta  | `aks-beta-fint-2021-11-23` | `fintlabs-no` | `kustomize/overlays/fintlabs-no/beta` |

Push til `main` bygger image (CI) og deployer til beta (CD). `MD`-workflowen bygger og deployer
manuelt. Produksjon (`api`) er ikke satt opp ennå.
