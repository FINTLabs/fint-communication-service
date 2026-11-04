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
meldings-ID. Meldingen legges i en kø i databasen og sendes asynkront via leverandøren som er
konfigurert (se [Utsending](#utsending)). Hver mottaker kan få høyst 10 meldinger per time og 40
per døgn (se [Grenser](#grenser)). Ingen e-postleverandør er koblet til ennå, så alle
miljøer bruker leverandøren `logging`, som logger og forkaster meldingen. Layout og autentisering kommer i egne oppgaver under
[FFS-1865](https://novari-iks.atlassian.net/browse/FFS-1865).

## Moduler

| Modul    | Innhold                                                                                | Artifact                              |
|----------|----------------------------------------------------------------------------------------|---------------------------------------|
| `app`    | Spring Boot-tjenesten (API og motor). Deployes, releases ikke.                         | –                                     |
| `model`  | API-kontrakten (request/response). Bare Jackson-annotasjoner, og bare ved kompilering. | `no.novari:fint-communication-model`  |
| `client` | HTTP-klient for APIet, blokkerende og reactive.                                        | `no.novari:fint-communication-client` |

`model` og `client` er kompilert for Java 21 og fungerer med både Spring Boot 3 og 4.

## Endepunkter

| Endepunkt                    | Bruk                                        |
|------------------------------|---------------------------------------------|
| `POST /api/v1/messages`      | Send melding                                |
| `/actuator/health`           | Startup-probe                               |
| `/actuator/health/liveness`  | Liveness-probe                              |
| `/actuator/health/readiness` | Readiness-probe                             |
| `/actuator/metrics`          | Metrikker som JSON, til feilsøking          |
| `/actuator/prometheus`       | Metrikker for Prometheus (scrapes av Flais) |

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

Eksemplene viser en tenkt mal. Repoet har foreløpig bare testmalen `novari/test` (én variabel,
`melding`), som brukes for å verifisere utsending i et miljø; innholdet i de første ekte malene er
ikke bestemt.

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
spam fra Novari, uansett hvilken tenant eller applikasjon som sender. Grensene per tenant og totalt
skal i tillegg stoppe en kompromittert eller feilkonfigurert avsender og holde kostnaden under
kontroll.

| Grense       | `limit`    | Verdi | Vindu             | Status          |
|--------------|------------|-------|-------------------|-----------------|
| Per mottaker | `mottaker` | 10    | Siste 60 minutter | Avgjort         |
| Per mottaker | `mottaker` | 40    | Siste 24 timer    | Avgjort         |
| Per tenant   | `tenant`   | 100   | Siste 60 minutter | Ikke bekreftet* |
| Per tenant   | `tenant`   | 500   | Siste 24 timer    | Ikke bekreftet* |
| Totalt       | `total`    | 500   | Siste 60 minutter | Ikke bekreftet* |
| Totalt       | `total`    | 2000  | Siste 24 timer    | Ikke bekreftet* |

\* Foreslåtte startverdier uten trafikktall. De må bekreftes med PO/Flais før produksjon.

- Mottakeren er adressen etter `trim` og små bokstaver, felles for alle tenants. `ola+test@rogfk.no`
  er en annen mottaker enn `ola@rogfk.no`.
- Tenantgrensen gjelder hver tenant for seg; når én tenant når grensen, kan de andre fortsatt sende.
  Totalgrensen gjelder alle tenants samlet.
- Grensene sjekkes i rekkefølgen mottaker → tenant → total. Bare meldinger som får `202`, teller; en
  avvist melding teller ikke mot noen av grensene.
- Overskredet grense gir `429 Too Many Requests` med `Retry-After` (sekunder til neste melding kan
  sendes) og `limit` som grensetype. Er flere grenser brutt, får svaret den som gir lengst ventetid.
  Adressen gjentas ikke.
- Er databasen utilgjengelig, avvises meldingen med `503` (fail-closed).
- Høyere grenser for enkeltmottakere innføres først når fylket eller mottakeren uttrykkelig har gitt
  tillatelse.

Grensene ligger i `app/src/main/resources/application.yaml` og endres med PR og deploy. En tenant kan
få egne grenser med en override. Nøkkelen er enum-navnet i `Tenant`, og både `per-hour` og `per-day`
må settes. Tenanter uten override bruker `default`.

```yaml
communication:
  limits:
    recipient:
      per-hour: 10
      per-day: 40
    tenant:
      default:
        per-hour: 100
        per-day: 500
      overrides:
        ROGALAND:
          per-hour: 200
          per-day: 1000
    total:
      per-hour: 500
      per-day: 2000
```

Ingen tenant har override i dag. NOVARI (test) bruker også `default`.

Avvisningene telles per grensetype og tenant, og utnyttelsen av tenant- og totalgrensen publiseres som
metrikker. Varsel går når en grense er over 80 % utnyttet eller avviser meldinger; se
[Drift og varsling](#drift-og-varsling).

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

## Utsending

Meldingen som får `202`, ligger i tabellen `dispatch_queue` til den er sendt eller har feilet.
Innholdet (adresse, emne og innhold) er kryptert med AES-GCM. En jobb i hver pod henter meldinger
som er klare, hvert andre sekund, og sender dem via `EmailAdapter`. Flere pods deler køen uten å
sende samme melding to ganger, og en melding som var under sending da en pod stoppet, sendes på
nytt etter 5 minutter.

| Utfall                                            | Hva skjer                                                                                                                    |
|---------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------|
| Sendt (leverandøren har tatt imot meldingen)      | Raden slettes. `communication_message_sent_total` øker.                                                                      |
| Forbigående feil (f.eks. nettverk, struping, 5xx) | Nytt forsøk etter 1, 2, 4 … minutter (høyst 1 time mellom forsøkene; ventetid fra leverandøren respekteres), høyst 8 forsøk. |
| Permanent feil (leverandøren avviser meldingen)   | Raden slettes. `communication_message_failed_total` øker, og en WARN-logg har årsak og feilkode fra leverandøren.            |
| Forsøkene brukt opp                               | Som permanent feil, med årsak `retries-exhausted`.                                                                           |

Konsumenten får ikke vite utfallet ennå; meldingsstatus per ID kommer i FFS-2337. Leverandøren
velges med `communication.email.provider`:

| Verdi     | Adapter               | Bruk                                                                                                        |
|-----------|-----------------------|-------------------------------------------------------------------------------------------------------------|
| `logging` | `LoggingEmailAdapter` | Default. Logger meldings-ID og mal og regner meldingen som sendt. Lokalt, i tester og i beta inntil videre. |

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

`bootTestRun` starter appen med en Postgres-container og testnøkler for mottaker-hashing og
kryptering av køen. Leverandøren er `logging`, så ingenting sendes. Testmalen viser hele flyten:

```bash
curl -i -X POST localhost:8080/api/v1/messages -H 'Content-Type: application/json' -d '{"tenant":"NOVARI","message":{"channel":"EMAIL","to":"test@novari.no","templateId":"novari/test","variables":{"melding":"Hei"}}}'
```

Loggen viser `Melding mottatt`, `E-post ikke sendt (provider=logging)` og `Melding sendt`.

Loggen er JSON, også lokalt og i testene. Lesbar tekst får man med et tomt format:

```bash
./gradlew :app:bootTestRun --args='--logging.structured.format.console='
```

## Konfigurasjon

| Variabel                                                                                                              | Innhold                                                                                                |
|-----------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------|
| `fint.database.url`, `fint.database.username`, `fint.database.password`                                               | Settes av Flais fra `spec.database` (`fint-common`).                                                   |
| `COMMUNICATION_RECIPIENT_HASHING_KEY`                                                                                 | HMAC-nøkkel for mottaker-hashing, base64, minst 32 bytes. Fra 1Password. Oppstarten feiler uten den.   |
| `COMMUNICATION_DISPATCH_ENCRYPTION_KEY`                                                                               | AES-nøkkel for innholdet i køen, base64, nøyaktig 32 bytes. Fra 1Password. Oppstarten feiler uten den. |
| `communication.email.provider`                                                                                        | `logging` (default). Ukjent verdi stopper oppstarten.                                                  |
| `communication.dispatch.poll-interval`, `communication.dispatch.batch-size`                                           | Hvor ofte køen sjekkes (`2s`) og hvor mange meldinger som hentes om gangen (`10`).                     |
| `communication.dispatch.lease`                                                                                        | Hvor lenge en melding er reservert av en pod før en annen kan ta den (`5m`).                           |
| `communication.dispatch.max-attempts`, `communication.dispatch.initial-backoff`, `communication.dispatch.max-backoff` | Antall forsøk og ventetid mellom dem (`8`, `1m`, `1h`).                                                |
| `communication.dispatch.queue-metrics-refresh`                                                                        | Hvor ofte kø-metrikkene regnes ut fra databasen (`60s`).                                               |
| `communication.limits.recipient.per-hour`, `communication.limits.recipient.per-day`                                   | Grenser per mottaker (10 og 40) i `application.yaml`.                                                  |
| `communication.limits.tenant.default.per-hour`, `communication.limits.tenant.default.per-day`                         | Grenser per tenant (100 og 500, ikke bekreftet) i `application.yaml`.                                  |
| `communication.limits.tenant.overrides.<TENANT>.per-hour`, `...per-day`                                               | Valgfri override per tenant. Nøkkelen er enum-navnet i `Tenant`, og begge feltene må settes.           |
| `communication.limits.total.per-hour`, `communication.limits.total.per-day`                                           | Grenser for alle tenants samlet (500 og 2000, ikke bekreftet) i `application.yaml`.                    |
| `communication.limits.usage-refresh`                                                                                  | Hvor ofte utnyttelsen av grensene regnes ut fra databasen. `60s` i `application.yaml`.                 |
| `logging.structured.format.console`                                                                                   | Loggformat. `logstash` (JSON til stdout) i `application.yaml`.                                         |
| `logging.level.<pakke>` (eller `LOGGING_LEVEL_<PAKKE>`)                                                               | Log-nivå per pakke, f.eks. `logging.level.no.novari.communication=DEBUG`. Default `INFO`.              |

Alle grenser må være større enn 0, og `per-day` minst like stor som `per-hour`. Tenantgrensene
(`default` og hver override) kan ikke være høyere enn totalgrensen. Ukjent tenant i `overrides` eller
en override med bare ett av feltene stopper også oppstarten.

Nye nøkler lages med `openssl rand -base64 32`. Bytter man krypteringsnøkkelen for køen, kan
meldinger som ligger i køen, ikke lenger dekrypteres, og de feiler etter siste forsøk. Bytt den
derfor når køen er tom (`communication_dispatch_queue_size` er 0). Bytter man hashing-nøkkelen, kjenner tjenesten ikke lenger igjen
mottakere som er lagret fra før. Se
[Mottaker-hashing](docs/architecture.md#mottaker-hashing).

Log-nivået endres uten kodeendring ved å legge en env-variabel i overlayet:

```yaml
- op: add
  path: "/spec/env/-"
  value:
    name: "logging.level.no.novari.communication"
    value: "DEBUG"
```

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

## Drift og varsling

Tjenesten publiserer disse metrikkene på `/actuator/prometheus`, i tillegg til Spring Boots egne. Ingen
av dem har adresse eller hash som tag.

| Metrikk                                       | Tagger                                            | Betydning                                                |
|-----------------------------------------------|---------------------------------------------------|----------------------------------------------------------|
| `communication_message_accepted_total`        | `tenant`, `channel`                               | Aksepterte meldinger (`202`).                            |
| `communication_limit_rejected_total`          | `limit` (`mottaker`, `tenant`, `total`), `tenant` | Meldinger avvist av en grense (`429`).                   |
| `communication_limit_tenant_usage_ratio`      | `tenant`, `window` (`hour`, `day`)                | Forbruk delt på tenantgrensen, siste time og siste døgn. |
| `communication_limit_total_usage_ratio`       | `window` (`hour`, `day`)                          | Forbruk delt på totalgrensen, siste time og siste døgn.  |
| `communication_message_sent_total`            | `tenant`, `channel`                               | Meldinger sendt til leverandøren.                        |
| `communication_message_retried_total`         | `tenant`, `channel`, `reason`                     | Forbigående feil som gir nytt forsøk.                    |
| `communication_message_failed_total`          | `tenant`, `channel`, `reason`                     | Meldinger som ikke ble sendt.                            |
| `communication_dispatch_queue_size`           | –                                                 | Meldinger i køen.                                        |
| `communication_dispatch_queue_oldest_seconds` | –                                                 | Alderen på den eldste meldingen i køen.                  |

Utnyttelsen og kø-metrikkene regnes ut fra databasen hvert minutt og er like på alle replikaer, så
bruk `max` og ikke `sum` på tvers av pods.

**Dashboard.** [`grafana/fint-communication-service.json`](grafana/fint-communication-service.json)
viser utnyttelse, avvisninger, aksepterte, sendte og feilede meldinger per tenant, og utsendingskøen. Det importeres manuelt i Grafana
(Dashboards → New → Import → last opp filen, og velg Prometheus-datakilden). Endringer gjøres i Grafana,
eksporteres som JSON og legges inn i repoet med PR.

**Varsling.** Varselreglene og hva vakthavende gjør, står i [runbooken](docs/runbook.md):

- utnyttelse av en tenant- eller totalgrense over 80 %,
- alle avvisninger på tenant- og totalgrensen,
- over 10 avvisninger på mottakergrensen per tenant per time,
- meldinger som ikke ble sendt,
- en melding som har ligget i køen i over 15 minutter.

Reglene registreres manuelt i Grafana Alerts med uttrykkene, tersklene og varighetene i runbooken.

## Deploy

| Miljø | Cluster                    | Namespace     | Overlay                               |
|-------|----------------------------|---------------|---------------------------------------|
| beta  | `aks-beta-fint-2021-11-23` | `fintlabs-no` | `kustomize/overlays/fintlabs-no/beta` |

Push til `main` bygger image (CI) og deployer til beta (CD). `MD`-workflowen bygger og deployer
manuelt. Produksjon (`api`) er ikke satt opp ennå.

Beta henter hemmeligheter fra 1Password-itemet `vaults/aks-beta-vault/items/fint-communication-service`,
som må ha feltene `COMMUNICATION_RECIPIENT_HASHING_KEY` og `COMMUNICATION_DISPATCH_ENCRYPTION_KEY`.
Mangler et av dem, starter ikke podden.

## Låsing av avhengigheter

Bygget bruker [Gradle dependency locking](https://docs.gradle.org/current/userguide/dependency_locking.html), slik at hvert bygg løser opp nøyaktig de samme direkte og transitive versjonene. De låste versjonene ligger i de committede `gradle.lockfile`-filene. En PR viser derfor alle transitive versjonsendringer den fører med seg. Gradle-plugins låses ikke, fordi Dependabot bare oppdaterer `gradle.lockfile`; versjonene deres er i stedet låst i `gradle/libs.versions.toml`.

**Automatisk (Dependabot):** Dependabot oppdaterer lockfilene i sine egne PR-er, så det trengs ingen manuelle steg. Hvis to Dependabot-PR-er kommer i konflikt i en lockfile, rebaser Dependabot den gjenværende etter at den første er merget. Skjer ikke det, kommenter `@dependabot recreate` på PR-en.

**Manuelt:** Alle endringer du gjør for hånd som påvirker oppløsningen av avhengigheter, krever at lockfilene regenereres. Det gjelder versjoner, `extra[...]`-properties, constraints og nye eller fjernede avhengigheter:

```bash
./gradlew dependencies :app:dependencies :model:dependencies :client:dependencies --write-locks
```

Nye subprosjekter må legges til i kommandoen. Commit de oppdaterte lockfilene sammen med endringen. Glemmer du det, feiler bygget både lokalt og i CI med en feilmelding om at den oppløste versjonen «is not part of the dependency lock state».
