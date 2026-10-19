# FINT Communication Service

Felles kommunikasjonstjeneste for Novari-plattformen. Applikasjoner og integrasjonsplattformen
sender meldinger hit, og tjenesten står for validering, layout og utsending via ekstern
leverandør. E-post er første kanal; SMS, Slack og webhooks skal kunne legges til uten vesentlig
endring i grunnarkitekturen.

Tjenesten er én multi-tenant deployment i namespace `fintlabs-no`, kun tilgjengelig internt i
clusteret. Tenant er organisasjonen meldingen sendes på vegne av: et fylke, Oslo kommune
(Bymiljøetaten), Riksantikvaren eller Novari (til testing). Gyldige tenants er definert i enumen
`Tenant` i `model`, og sendes som enum-navnet (f.eks. `"ROGALAND"`).

Se [docs/architecture.md](docs/architecture.md) for arkitektur, dataflyt og sekvenser.

## Status

`POST /api/v1/messages` tar imot e-post basert på maler og svarer `202 Accepted` med en
meldings-ID. Meldingen sendes ikke ennå. Hver mottaker kan få høyst 10 meldinger per time og 40
per døgn (se [Grenser](#grenser)). Layout, leverandøradapter og autentisering kommer i
egne oppgaver under [FFS-1865](https://novari-iks.atlassian.net/browse/FFS-1865).

## Moduler

| Modul    | Innhold                                                                                | Artifact                              |
|----------|----------------------------------------------------------------------------------------|---------------------------------------|
| `app`    | Spring Boot-tjenesten (API og motor). Deployes, releases ikke.                         | –                                     |
| `model`  | API-kontrakten (request/response). Bare Jackson-annotasjoner, og bare ved kompilering. | `no.novari:fint-communication-model`  |
| `client` | HTTP-klient for APIet, blokkerende og reactive.                                        | `no.novari:fint-communication-client` |

`model` og `client` er kompilert for Java 21 og fungerer med både Spring Boot 3 og 4.

## Endepunkter

| Endepunkt                    | Bruk            |
|------------------------------|-----------------|
| `POST /api/v1/messages`      | Send melding    |
| `/actuator/health`           | Startup-probe   |
| `/actuator/health/liveness`  | Liveness-probe  |
| `/actuator/health/readiness` | Readiness-probe |

## Sende melding

All e-post sendes via en mal. Klienten oppgir mal, mottaker og verdiene til malens variabler,
men aldri emne eller innhold. `channel` bestemmer meldingstypen; i dag støttes bare `EMAIL`.

```json
{
  "tenant": "ROGALAND",
  "message": {
    "channel": "EMAIL",
    "to": "ola@rogfk.no",
    "templateId": "flyt/integrasjonsfeil",
    "variables": { "antallFeil": "8", "fra": "06.10.2026 kl. 09.00", "til": "06.10.2026 kl. 12.00" },
    "lists": {
      "integrasjoner": [
        { "navn": "ACOS", "antallFeil": "3" },
        { "navn": "eGrunnerverv", "antallFeil": "4" },
        { "navn": "eApply", "antallFeil": "1" }
      ]
    }
  }
}
```

Eksemplene viser en tenkt mal. Repoet inneholder foreløpig ingen maler; innholdet i de første
malene er ikke bestemt.

Gyldig request gir `202 Accepted` med `{"id": "<uuid>"}`. Ugyldig request gir `400` som
`application/problem+json` (RFC 9457), med ett element i `errors` per ugyldig felt. Meldingene
gjentar aldri verdiene som ble sendt inn.

```json
{
  "title": "Bad Request",
  "status": 400,
  "detail": "Requesten inneholder ugyldige felt",
  "instance": "/api/v1/messages",
  "errors": [{ "field": "message.variables.antallFeil", "message": "kan ikke være lengre enn 6 tegn" }]
}
```

## Grenser

Tjenesten er et sikkerhetsnett over konsumentenes egne grenser: en mottaker skal ikke kunne få
spam fra Novari, uansett hvilken tenant eller applikasjon som sender.

| Grense       | Verdi | Vindu             |
|--------------|-------|-------------------|
| Per mottaker | 10    | Siste 60 minutter |
| Per mottaker | 40    | Siste 24 timer    |

- Mottakeren er adressen etter `trim` og små bokstaver, felles for alle tenants. `ola+test@rogfk.no`
  er en annen mottaker enn `ola@rogfk.no`.
- Bare meldinger som får `202`, teller. En avvist melding teller ikke.
- Overskredet grense gir `429 Too Many Requests` med `Retry-After` (sekunder til neste melding kan
  sendes) og `limit` som grensetype. Adressen gjentas ikke.
- Er databasen utilgjengelig, avvises meldingen med `503` (fail-closed).
- Høyere grenser for enkeltmottakere innføres først når fylket eller mottakeren uttrykkelig har gitt
  tillatelse.

```json
HTTP/1.1 429
Retry-After: 3000

{
  "title": "Too Many Requests",
  "status": 429,
  "detail": "Mottakeren har fått for mange meldinger. Prøv igjen senere.",
  "instance": "/api/v1/messages",
  "limit": "mottaker"
}
```

## Maler

Malene ligger i `app/src/main/resources/templates/<team>/email/<mal>/`, og mal-ID-en er
`<team>/<mal>`. Hvert team eier sin mappe via `.github/CODEOWNERS`, og nye maler godkjennes
gjennom PR.

`template.yaml` beskriver malen:

```yaml
description: Hva malen brukes til
subject: "Flyt: {{antallFeil}} feil på integrasjoner"
replyTo: no-reply@novari.no   # valgfri
variables:
  antallFeil: { maxLength: 6 }
lists:
  integrasjoner:
    maxItems: 100
    fields:
      navn: { maxLength: 100 }
```

`body.html` inneholder selve innholdet i Mustache. Layout rundt innholdet legges på sentralt.

Regler, som sjekkes ved oppstart og i testene:

- Alle variabler er påkrevde strenger med `maxLength` (høyst 1000). Verdier kan ikke inneholde
  linjeskift eller andre kontrolltegn.
- Lister brukes bare som section (`{{#integrasjoner}}…{{/integrasjoner}}`). De har `maxItems`
  (høyst 100), minst ett element, og inne i listen kan bare listens egne felt brukes.
- Alt som er deklarert, må brukes, og alt som brukes, må være deklarert.
- Ikke tillatt: `{{{ }}}`, `{{& }}`, inverterte sections, partials, nøstede sections og
  endring av delimitere.
- Verdier HTML-escapes i `body.html`. `subject` er ren tekst og må være én linje.

## Lokal utvikling

Krever Java 25 og Docker. Testene og lokal kjøring starter Postgres med Testcontainers.

```bash
./gradlew check
```

`check` kjører tester og ktlint i alle moduler. Klienten testes mot både Spring Framework 6.2
med Jackson 2 (`:client:test`) og Spring Framework 7 med Jackson 3 (`:client:testSpring7`).

```bash
./gradlew :app:bootTestRun
```

`bootTestRun` starter appen med en Postgres-container og en testnøkkel for mottaker-hashing.

## Konfigurasjon

| Variabel                                                                            | Innhold                                                                                                                                            |
|-------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| `fint.database.url`, `fint.database.username`, `fint.database.password`             | Settes av Flais fra `spec.database` (`fint-common`).                                                                                               |
| `COMMUNICATION_RECIPIENT_HASHING_KEY`                                               | HMAC-nøkkel for mottaker-hashing, base64, minst 32 bytes. Fra 1Password. Oppstarten feiler uten den.                                               |
| `communication.limits.recipient.per-hour`, `communication.limits.recipient.per-day` | Grenser per mottaker (10 og 40) i `application.yaml`. Må være større enn 0, og `per-day` minst like stor som `per-hour`; ellers feiler oppstarten. |

Ny nøkkel lages med `openssl rand -base64 32`. Bytter man nøkkel, kjenner tjenesten ikke lenger igjen
mottakere som er lagret fra før; se [Database](docs/architecture.md#database).

Flyway kjører migreringene i `app/src/main/resources/db/migration` ved oppstart.

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
            tenant = Tenant.ROGALAND,
            message =
                EmailMessage(
                    to = "ola@rogfk.no",
                    templateId = "flyt/integrasjonsfeil",
                    variables = mapOf("antallFeil" to "8", "fra" to "06.10.2026 kl. 09.00", "til" to "06.10.2026 kl. 12.00"),
                    lists = mapOf("integrasjoner" to listOf(mapOf("navn" to "ACOS", "antallFeil" to "8"))),
                ),
        ),
    )
```

For reactive applikasjoner: `CommunicationClients.createReactive(webClient)`, som returnerer
`Mono<MessageAcceptedResponse>`.

### Feil fra tjenesten

Klienten kaster Springs vanlige unntak: `RestClientResponseException` (blokkerende) og
`WebClientResponseException` (reactive), med ProblemDetail i bodyen. `429` betyr at mottakeren har
nådd en grense (se [Grenser](#grenser)). Meldingen er ikke lagret, og den bør enten droppes eller
sendes på nytt etter `Retry-After`. Ikke prøv på nytt i en løkke.

```kotlin
try {
    client.send(request)
} catch (e: HttpClientErrorException.TooManyRequests) {
    val retryAfterSeconds = e.responseHeaders?.getFirst(HttpHeaders.RETRY_AFTER)?.toLongOrNull()
    val limit = e.getResponseBodyAs(ProblemDetail::class.java)?.properties?.get("limit")
    // f.eks. logg og dropp, eller planlegg nytt forsøk etter retryAfterSeconds
}
```

For `WebClient` er tilsvarende unntak `WebClientResponseException.TooManyRequests`. `503` betyr at
tjenesten er midlertidig utilgjengelig og kan prøves på nytt senere.

## Release av bibliotekene

Opprett en GitHub-release med tag `vX.Y.Z`. `Publish to Reposilite`-workflowen publiserer da
`model` og `client` med versjon `X.Y.Z`.

## Deploy

| Miljø | Cluster                    | Namespace     | Overlay                               |
|-------|----------------------------|---------------|---------------------------------------|
| beta  | `aks-beta-fint-2021-11-23` | `fintlabs-no` | `kustomize/overlays/fintlabs-no/beta` |

Push til `main` bygger image (CI) og deployer til beta (CD). `MD`-workflowen bygger og deployer
manuelt. Produksjon (`api`) er ikke satt opp ennå.

Beta henter hemmeligheter fra 1Password-itemet `vaults/aks-beta-vault/items/fint-communication-service`,
som må ha feltet `COMMUNICATION_RECIPIENT_HASHING_KEY`.
