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
per døgn (se [Grenser](#grenser)), og mottakere som har meldt seg av eller hard-bouncet, avvises
(se [Blokkeringsliste](#blokkeringsliste)). Layout, leverandøradapter og autentisering kommer i
egne oppgaver under [FFS-1865](https://novari-iks.atlassian.net/browse/FFS-1865).

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

## Blokkeringsliste

Mottakere som har meldt seg av (`OPT_OUT`) eller hard-bouncet (`HARD_BOUNCE`), får ikke e-post fra
tjenesten. Listen er felles for alle tenants og sjekkes ved innsending, før grensene.

| Årsak         | Varighet                   | Legges inn av                                                                                            |
|---------------|----------------------------|----------------------------------------------------------------------------------------------------------|
| `OPT_OUT`     | Til den fjernes manuelt.   | [Driftsrutinen](#driftsrutine-for-blokkeringslisten).                                                    |
| `HARD_BOUNCE` | 30 dager fra siste bounce. | Driftsrutinen. Senere automatisk fra ACS ([FFS-2338](https://novari-iks.atlassian.net/browse/FFS-2338)). |

- Mottakeren kjennes igjen på samme måte som for grensene: adressen etter `trim` og små bokstaver.
- En blokkert mottaker gir `422 Unprocessable Content`. Svaret sier ikke om årsaken er opt-out eller
  bounce, og gjentar ikke adressen. Meldingen lagres ikke og teller ikke mot grensene.
- Listen lagrer bare HMAC-hashen av adressen, aldri adressen.
- En utløpt blokkering gjelder ikke lenger og slettes av den nattlige opprydningen.
- Er databasen utilgjengelig, avvises meldingen med `503` (fail-closed).

```json
HTTP/1.1 422

{
  "title": "Unprocessable Content",
  "status": 422,
  "detail": "Mottakeren kan ikke motta e-post fra tjenesten.",
  "instance": "/api/v1/messages"
}
```

### Driftsrutine for blokkeringslisten

Det finnes ingen avmeldingslenke eller admin-API ennå
([FFS-2340](https://novari-iks.atlassian.net/browse/FFS-2340)). Opt-out legges inn og blokkeringer
fjernes med SQL mot databasen `fint-common`, etter at hashen av adressen er beregnet.

**1. Beregn hashen i podden.** Nøkkelen ligger allerede i podden, så den forlater aldri clusteret, og
du trenger ikke tilgang til 1Password-itemet. Imaget har ikke shell, men `kubectl exec` starter `java`
direkte. Legg adressene i en fil, én per linje, og send filen inn på stdin, så adressene ikke havner i
shell-historikken eller prosesslisten:

```bash
kubectl exec -i -n fintlabs-no deploy/fint-communication-service -- java -Xmx64m -cp /app/app.jar -Dloader.main=no.novari.communication.recipient.RecipientHashCliKt org.springframework.boot.loader.launch.PropertiesLauncher < adresser.txt
```

Utdata er én hash (64 hex-tegn) per adresse, i samme rekkefølge. Blanke linjer hoppes over.
Verktøyet bruker samme `RecipientHasher` som tjenesten og skriver verken adressen eller nøkkelen.
`-Xmx64m` hindrer at den ekstra JVM-en tar minne fra tjenesten, som har samme minnegrense (512 Mi).
Slett filen etterpå.

**2. Kjør SQL** med hashen i stedet for `<hash>`.

Legg til opt-out (gjør ingenting hvis den finnes fra før):

```sql
INSERT INTO recipient_blocklist (recipient_hash, reason, source, expires_at)
VALUES ('<hash>', 'OPT_OUT', 'MANUAL', NULL)
ON CONFLICT (recipient_hash, reason) DO NOTHING;
```

Legg inn hard bounce manuelt (30 dager; forlenger en eksisterende blokkering, men forkorter den aldri):

```sql
INSERT INTO recipient_blocklist (recipient_hash, reason, source, expires_at)
VALUES ('<hash>', 'HARD_BOUNCE', 'MANUAL', now() + interval '30 days')
ON CONFLICT (recipient_hash, reason)
DO UPDATE SET expires_at = GREATEST(recipient_blocklist.expires_at, EXCLUDED.expires_at);
```

Se status:

```sql
SELECT reason, source, created_at, expires_at FROM recipient_blocklist WHERE recipient_hash = '<hash>';
```

Fjern opt-out (en aktiv hard bounce står igjen):

```sql
DELETE FROM recipient_blocklist WHERE recipient_hash = '<hash>' AND reason = 'OPT_OUT';
```

Fjern all blokkering av mottakeren:

```sql
DELETE FROM recipient_blocklist WHERE recipient_hash = '<hash>';
```

Tabellen avviser ugyldige kombinasjoner: `OPT_OUT` kan ikke ha utløp, og `HARD_BOUNCE` må ha det.
SQL-en over er dekket av `BlocklistIntegrationTest`.

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

Loggen er JSON, også lokalt og i testene. Lesbar tekst får man med et tomt format:

```bash
./gradlew :app:bootTestRun --args='--logging.structured.format.console='
```

## Konfigurasjon

| Variabel                                                                                      | Innhold                                                                                              |
|-----------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------|
| `fint.database.url`, `fint.database.username`, `fint.database.password`                       | Settes av Flais fra `spec.database` (`fint-common`).                                                 |
| `COMMUNICATION_RECIPIENT_HASHING_KEY`                                                         | HMAC-nøkkel for mottaker-hashing, base64, minst 32 bytes. Fra 1Password. Oppstarten feiler uten den. |
| `communication.limits.recipient.per-hour`, `communication.limits.recipient.per-day`           | Grenser per mottaker (10 og 40) i `application.yaml`.                                                |
| `communication.limits.tenant.default.per-hour`, `communication.limits.tenant.default.per-day` | Grenser per tenant (100 og 500, ikke bekreftet) i `application.yaml`.                                |
| `communication.limits.tenant.overrides.<TENANT>.per-hour`, `...per-day`                       | Valgfri override per tenant. Nøkkelen er enum-navnet i `Tenant`, og begge feltene må settes.         |
| `communication.limits.total.per-hour`, `communication.limits.total.per-day`                   | Grenser for alle tenants samlet (500 og 2000, ikke bekreftet) i `application.yaml`.                  |
| `logging.structured.format.console`                                                           | Loggformat. `logstash` (JSON til stdout) i `application.yaml`.                                       |
| `logging.level.<pakke>` (eller `LOGGING_LEVEL_<PAKKE>`)                                       | Log-nivå per pakke, f.eks. `logging.level.no.novari.communication=DEBUG`. Default `INFO`.            |

Alle grenser må være større enn 0, og `per-day` minst like stor som `per-hour`. Tenantgrensene
(`default` og hver override) kan ikke være høyere enn totalgrensen. Ukjent tenant i `overrides` eller
en override med bare ett av feltene stopper også oppstarten.

Ny nøkkel lages med `openssl rand -base64 32`. Bytter man nøkkel, kjenner tjenesten ikke lenger igjen
mottakere som er lagret fra før. Det gjelder også blokkeringslisten: opt-outs må da registreres på nytt,
og hard bounces bygges opp igjen først når ACS rapporterer dem på nytt. Se
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
`WebClientResponseException` (reactive), med ProblemDetail i bodyen. `422` betyr at mottakeren er
blokkert (se [Blokkeringsliste](#blokkeringsliste)); meldingen bør droppes, siden et nytt forsøk gir
samme svar. `429` betyr at mottakeren har
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
