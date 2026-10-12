# Runbook: grenser, blokkerte innsendinger, utsending og leveringsrapporter

Runbooken beskriver hva vakthavende gjør når et varsel for fint-communication-service går. Grensene og
blokkeringslisten er beskrevet i [README](../README.md#grenser) og
[arkitekturdokumentet](architecture.md#grenser), utsendingen i [README](../README.md#utsending) og
[arkitekturdokumentet](architecture.md#hva-skjer-med-meldingen-etter-202), og leveringsrapportene i
[README](../README.md#leveringsrapporter) og [arkitekturdokumentet](architecture.md#leveringsrapporter).

## Innhold

1. [Metrikker](#metrikker)
2. [Varselregler](#varselregler)
3. [Første steg ved alle varsler](#første-steg-ved-alle-varsler)
4. [Høy utnyttelse](#høy-utnyttelse)
5. [Tenantgrense nådd](#tenantgrense-nådd)
6. [Totalgrense nådd](#totalgrense-nådd)
7. [Mange mottaker-429](#mange-mottaker-429)
8. [Mange blokkerte innsendinger](#mange-blokkerte-innsendinger)
9. [Meldinger feilet](#meldinger-feilet)
10. [Køen står](#køen-står)
11. [Høy bounce-rate](#høy-bounce-rate)
12. [Rapporter forkastet](#rapporter-forkastet)
13. [Rapportkøen vokser](#rapportkøen-vokser)
14. [Mottak av rapporter feiler](#mottak-av-rapporter-feiler)
15. [Slå opp en melding](#slå-opp-en-melding)
16. [Justere grenser](#justere-grenser)
17. [Testvarsel](#testvarsel)
18. [Verifisere utsending](#verifisere-utsending)
19. [Verifisere leveringsrapporter](#verifisere-leveringsrapporter)

## Metrikker

| Metrikk (Prometheus)                              | Type    | Tagger                                                                                                                  | Betydning                                                        |
|---------------------------------------------------|---------|-------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------|
| `communication_limit_rejected_total`              | Counter | `limit` (`mottaker`, `tenant`, `total`), `tenant`                                                                       | Meldinger avvist med 429.                                        |
| `communication_blocklist_rejected_total`          | Counter | `tenant`                                                                                                                | Meldinger avvist med 422 fordi mottakeren er blokkert.           |
| `communication_message_accepted_total`            | Counter | `tenant`, `channel`                                                                                                     | Meldinger akseptert med 202.                                     |
| `communication_limit_tenant_usage_ratio`          | Gauge   | `tenant`, `window` (`hour`, `day`)                                                                                      | Forbruk delt på tenantens grense i glidende vindu.               |
| `communication_limit_total_usage_ratio`           | Gauge   | `window` (`hour`, `day`)                                                                                                | Forbruk delt på totalgrensen i glidende vindu.                   |
| `communication_message_sent_total`                | Counter | `tenant`, `channel`                                                                                                     | Meldinger sendt til leverandøren.                                |
| `communication_message_retried_total`             | Counter | `tenant`, `channel`, `reason` (`timeout`, `throttled`, `server-error`, `unauthorized`, `io`, `unexpected`)              | Forbigående feil; meldingen prøves igjen.                        |
| `communication_message_failed_total`              | Counter | `tenant`, `channel`, `reason` (`rejected`, `operation-failed`, `retries-exhausted`)                                     | Meldinger som ikke ble sendt.                                    |
| `communication_dispatch_queue_size`               | Gauge   | –                                                                                                                       | Meldinger i køen (ikke sendt eller feilet ennå).                 |
| `communication_dispatch_queue_oldest_seconds`     | Gauge   | –                                                                                                                       | Alderen på den eldste meldingen i køen.                          |
| `communication_delivery_report_received_total`    | Counter | `tenant`, `channel`, `template`, `status` (`DELIVERED`, `BOUNCED`, `SUPPRESSED`, `FILTERED_SPAM`, `FAILED`, `EXPANDED`) | Leveringsrapporter for kjente meldinger, per status i rapporten. |
| `communication_delivery_report_discarded_total`   | Counter | `reason` (`invalid`, `unsupported-type`, `unknown-message`, `poison`)                                                   | Rapporter som ble forkastet.                                     |
| `communication_delivery_report_poll_failed_total` | Counter | –                                                                                                                       | Feil mot rapportkøen (henting eller sletting).                   |
| `communication_delivery_report_queue_size`        | Gauge   | –                                                                                                                       | Rapporter som venter i køen (omtrentlig, fra Azure).             |

Utnyttelsen regnes ut fra `send_usage` hvert minutt (`communication.limits.usage-refresh`), og kø-metrikkene
fra `dispatch_queue` hvert minutt (`communication.dispatch.queue-metrics-refresh`), så de kan ligge opptil ett
minutt etter. Hver replika rapporterer samme verdi, så bruk `max`, ikke `sum`, på tvers av pods. Ingen
metrikk har adresse eller hash. Metrikkene for leveringsrapporter finnes bare når mottaket er slått på
(`communication.email.delivery-reports.enabled`). `template` er mal-ID-en, så bounce-raten kan
brytes ned per mal.

Dashboardet ligger i [`grafana/fint-communication-service.json`](../grafana/fint-communication-service.json).

## Varselregler

Varslene registreres manuelt i Grafana Alerts med metrikkene, tersklene og varighetene under.
Tabellen er kilden: endres en regel i Grafana, oppdateres den her også.
`$app` står for `app="fint-communication-service"`.

| Varsel                       | Uttrykk                                                                                                                                                                                                                                                  | I minst | Alvorlighet |
|------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------|-------------|
| Høy utnyttelse, tenant       | `max by (tenant, window) (communication_limit_tenant_usage_ratio{$app}) > 0.8`                                                                                                                                                                           | 5m      | warning     |
| Høy utnyttelse, total        | `max by (window) (communication_limit_total_usage_ratio{$app}) > 0.8`                                                                                                                                                                                    | 5m      | warning     |
| Tenantgrense nådd            | `sum by (tenant) (increase(communication_limit_rejected_total{$app, limit="tenant"}[5m])) > 0`                                                                                                                                                           | –       | warning     |
| Totalgrense nådd             | `sum (increase(communication_limit_rejected_total{$app, limit="total"}[5m])) > 0`                                                                                                                                                                        | –       | critical    |
| Mange mottaker-429           | `sum by (tenant) (increase(communication_limit_rejected_total{$app, limit="mottaker"}[1h])) > 10`                                                                                                                                                        | –       | warning     |
| Mange blokkerte innsendinger | `sum by (tenant) (increase(communication_blocklist_rejected_total{$app}[1h])) > 10`                                                                                                                                                                      | –       | warning     |
| Meldinger feilet             | `sum by (tenant, reason) (increase(communication_message_failed_total{$app}[15m])) > 0`                                                                                                                                                                  | –       | warning     |
| Køen står                    | `max(communication_dispatch_queue_oldest_seconds{$app}) > 900`                                                                                                                                                                                           | 5m      | critical    |
| Høy bounce-rate              | `sum(increase(communication_delivery_report_received_total{$app, status="BOUNCED"}[1h])) / sum(increase(communication_delivery_report_received_total{$app}[1h])) > 0.05 and sum(increase(communication_delivery_report_received_total{$app}[1h])) >= 20` | –       | warning     |
| Rapporter forkastet          | `sum(increase(communication_delivery_report_discarded_total{$app, reason="poison"}[15m])) > 0`                                                                                                                                                           | –       | warning     |
| Rapportkøen vokser           | `max(communication_delivery_report_queue_size{$app}) > 1000`                                                                                                                                                                                             | 15m     | warning     |
| Mottak av rapporter feiler   | `sum(increase(communication_delivery_report_poll_failed_total{$app}[5m])) > 0`                                                                                                                                                                           | 15m     | warning     |

Alle avvisninger på tenant- og totalgrensen varsles, siden de betyr at meldinger ikke blir sendt. Det
samme gjelder alle meldinger som feiler etter `202`, siden konsumenten ikke får vite om det (meldingsstatus
kommer i FFS-2337).
Bounce-raten varsles først fra 20 rapporter i timen, så én bounce i en stille time ikke gir varsel.
Tersklene for leveringsrapportene er forslag og justeres når vi har tall fra beta.
Avvisninger på mottakergrensen er forventet i små mengder (en konsument som sender flere varsler til
samme person), så de varsles først over 10 per time per tenant.

## Første steg ved alle varsler

1. Åpne dashboardet og finn tenanten og tidspunktet.
2. Søk i Loki etter WARN-loggen. Den har grensetype, tenant og meldings-ID, men aldri adresse eller
   hash. Label-navnene avhenger av oppsettet i Loki; i `fintlabs-no` er det typisk:

   ```logql
   {namespace="fintlabs-no", app="fint-communication-service"} |= "Grense overskredet" | json | line_format "{{.message}}"
   ```

   For blokkerte innsendinger søker du etter `Mottaker blokkert`.
3. Se forbruket per tenant og time direkte i databasen `fint-common` (bare siste døgn finnes):

   ```sql
   SELECT tenant, date_trunc('hour', sent_at) AS time, count(*)
   FROM send_usage
   GROUP BY tenant, time
   ORDER BY time DESC, count(*) DESC;
   ```

4. Finn ut hvilken konsument som sender for tenanten. Tenant oppgis i requesten, så inntil
   autentisering er på plass (FFS-1970) kan det være flere applikasjoner bak samme tenant.

## Høy utnyttelse

**Betyr:** en tenant eller totalen har brukt over 80 % av grensen i siste time eller siste døgn.
Ingenting er avvist ennå.

**Sannsynlige årsaker:** legitim vekst (ny konsument, ny mal, et fylke med mange varsler), en
konsument med feil (løkke, retry uten backoff) eller en kompromittert konsument.

**Gjør:**

- Sammenlign med aksepterte meldinger over tid: jevn vekst tyder på legitim bruk, en brå topp på feil.
- Ved feil eller mistanke om kompromittering: kontakt teamet som eier konsumenten.
- Ved legitim vekst: vurder å [justere grensen](#justere-grenser) før den nås.

## Tenantgrense nådd

**Betyr:** meldinger fra tenanten avvises med `429` og `limit: "tenant"`. Andre tenants kan fortsatt
sende. Konsumenten får `Retry-After` og må prøve igjen senere.

**Gjør:**

- Som for høy utnyttelse, men raskere: meldinger går tapt hvis konsumenten ikke prøver igjen.
- Grensen slipper av seg selv når de eldste meldingene glir ut av vinduet (senest etter 1 time for
  timegrensen og 24 timer for døgngrensen). Det trengs ingen opprydning.
- Er trafikken legitim og haster det, [øk grensen](#justere-grenser) for tenanten med en override.

## Totalgrense nådd

**Betyr:** alle tenants avvises med `429` og `limit: "total"`. Tjenesten sender ingenting før vinduet
har glidd.

**Gjør:**

- Finn tenanten som står for mesteparten av forbruket (dashboardet eller SQL-en over). Ofte er det én
  tenant som også har nådd sin egen grense.
- Mistanke om kompromittering: kontakt teamet som eier konsumenten, og vurder å senke grensen for
  tenanten med en override, slik at de andre slipper til.
- Legitim økning: totalgrensen er satt for å holde kostnaden under kontroll. Avklar en økning med PO
  før den gjøres.

## Mange mottaker-429

**Betyr:** over 10 meldinger per time fra en tenant er avvist fordi samme mottaker har fått for mange
(10 per time, 40 per døgn).

**Sannsynlige årsaker:** en konsument som sender ett varsel per hendelse i stedet for en
oppsummering, eller som ignorerer `429` og prøver igjen uten å vente.

**Gjør:** kontakt teamet som eier konsumenten. Mottakergrensen økes ikke uten at fylket eller
mottakeren uttrykkelig har gitt tillatelse.

## Mange blokkerte innsendinger

**Betyr:** over 10 meldinger per time fra en tenant er avvist med `422` fordi mottakeren har meldt
seg av eller hard-bouncet.

**Sannsynlige årsaker:** konsumenten håndterer ikke `422` og fortsetter å sende til samme mottaker,
eller en mottakerliste har mange døde adresser.

**Gjør:**

- Kontakt teamet som eier konsumenten. `422` betyr at et nytt forsøk ikke hjelper.
- Hvis en mottaker er blokkert ved en feil, fjernes blokkeringen med
  [driftsrutinen for blokkeringslisten](../README.md#driftsrutine-for-blokkeringslisten).

## Meldinger feilet

**Betyr:** en eller flere meldinger som fikk `202`, ble ikke sendt. Raden i køen er slettet, og
meldingen sendes ikke på nytt av seg selv. Status er `FAILED` med årsak, og konsumenten ser det med
`GET /api/v1/messages/{id}` (se [Slå opp en melding](#slå-opp-en-melding)).

**Finn årsaken:** søk i Loki etter `Melding feilet`. Linjen har meldings-ID, tenant, mal, antall
forsøk, årsak og detalj (HTTP-status og ACS-feilkode), aldri adresse eller innhold.

| `reason`            | Betyr                                                                                     | Gjør                                                                                                                      |
|---------------------|-------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------|
| `rejected`          | ACS avviste requesten (4xx), f.eks. ugyldig avsender eller domene som ikke er verifisert. | Gjelder det alle meldinger, er det konfig: sjekk `communication.email.sender` mot avsenderen i ACS. Ellers: se feilkoden. |
| `operation-failed`  | ACS godtok requesten, men sendingen feilet, f.eks. `EmailDroppedAllRecipientsSuppressed`. | Mottakeren er undertrykt hos ACS (bounce eller avmelding). Kontakt teamet som eier konsumenten hvis det gjelder mange.    |
| `retries-exhausted` | Alle forsøk (8, over ca. 2 timer) ga forbigående feil.                                    | Se [Køen står](#køen-står): ACS har trolig vært utilgjengelig, strupet trafikken eller avvist nøkkelen over lengre tid.   |

Meldinger som har feilet, må sendes på nytt av konsumenten. Kontakt teamet som eier konsumenten med
meldings-ID-ene fra loggen.

## Køen står

**Betyr:** den eldste meldingen i køen er over 15 minutter gammel. Meldinger sendes sent eller ikke
i det hele tatt.

**Finn årsaken:**

- `communication_message_retried_total` per `reason` viser hvorfor sendingen feiler:
  - `server-error`, `timeout` eller `io`: ACS er utilgjengelig eller treg.
  - `throttled`: ACS struper trafikken. Køen tømmes av seg selv når ACS slipper til igjen.
  - `unauthorized`: connection string er feil eller rotert. Sjekk
    `COMMUNICATION_EMAIL_ACS_CONNECTION_STRING` i 1Password-itemet og restart podden.
  - `unexpected` med `detalj=javax.crypto.AEADBadTagException`: meldingen kan ikke dekrypteres, fordi
    `COMMUNICATION_DISPATCH_ENCRYPTION_KEY` er byttet eller feil. Rettes nøkkelen før forsøkene er brukt
    opp, sendes meldingene; ellers feiler de med `retries-exhausted`.
- I Loki: `Utsending feilet, prøver igjen` (med årsak og ACS-feilkode) og `Kunne ikke hente meldinger fra køen`
  (databasen).
- Ingen nye forsøk og ingen feil i loggen: sjekk at podden kjører. Køen ligger i databasen, så en
  restart mister ingen meldinger; meldinger som var under sending, tas på nytt etter 5 minutter.
- Status i køen direkte i `fint-common`:

  ```sql
  SELECT locked_until IS NOT NULL AS reservert, attempts, count(*), min(m.received_at), min(next_attempt_at)
  FROM dispatch_queue JOIN message m USING (message_id)
  GROUP BY reservert, attempts
  ORDER BY attempts;
  ```

## Høy bounce-rate

**Betyr:** mer enn 5 % av leveringsrapportene siste time er `BOUNCED`. Adressene blokkeres
automatisk i 30 dager, men en høy rate kan skade omdømmet til avsenderdomenet, og ACS kan strupe eller
stenge sendingen.

**Finn årsaken:**

- `sum by (tenant, template) (increase(communication_delivery_report_received_total{$app, status="BOUNCED"}[1h]))`
  viser hvilken tenant og mal det gjelder. Ofte er det én konsument med en gammel eller feil
  adresseliste.
- I Loki: `Leveringsrapport` med `acsStatus=Bounced` gir meldings-ID-ene, uten adresse.
- Kontakt teamet som eier konsumenten. Tjenesten har ikke adressene; konsumenten må finne dem via
  meldings-ID-ene i sitt eget system.

## Rapporter forkastet

**Betyr:** en leveringsrapport er hentet 5 ganger uten å bli lagret, og er slettet (poison). Status
for meldingen er ikke oppdatert, og en ev. bounce er ikke blokkert.

**Finn årsaken:** søk i Loki etter `Kunne ikke lagre leveringsrapporten`. Linjen har meldings-ID og
unntakstypen. Det skyldes nesten alltid at databasen har vært utilgjengelig i over 20 minutter
(5 × `visibility-timeout`); sjekk `db` i readiness og databasen i `fint-common`. Hendelsen kan ikke
hentes tilbake. Gjaldt den en bounce, blokkeres adressen neste gang ACS rapporterer den
(`Suppressed`).

`reason="invalid"` og `reason="unsupported-type"` varsles ikke, men betyr at abonnementet i Event Grid
sender noe annet enn CloudEvents med `EmailDeliveryReportReceived` (se
[Slå på leveringsrapporter](../README.md#slå-på-leveringsrapporter)). `reason="unknown-message"` er
forventet i små mengder: rapporter for meldinger som er slettet etter 60 dager, eller sendt fra ACS
uten om tjenesten.

## Rapportkøen vokser

**Betyr:** over 1000 rapporter har ventet i køen i 15 minutter. Status og blokkering blir oppdatert
sent.

**Finn årsaken:** sjekk først [Mottak av rapporter feiler](#mottak-av-rapporter-feiler). Feiler ikke
hentingen, sjekk at podden kjører og at `communication.email.delivery-reports.enabled` er `true`.
Køen holder rapportene i inntil 7 dager (standard i Azure Storage), og ingenting går tapt så lenge
den tømmes innen det.

## Mottak av rapporter feiler

**Betyr:** tjenesten har ikke fått kontakt med rapportkøen på 15 minutter. Utsending og mottak av
meldinger virker som før; rapportene blir liggende i køen.

**Finn årsaken:** søk i Loki etter `Kunne ikke hente leveringsrapporter fra køen`. Linjen har bare
unntakstypen.

- `QueueStorageException` med 403: connection string er feil eller rotert. Sjekk
  `COMMUNICATION_EMAIL_DELIVERY_REPORTS_CONNECTION_STRING` i 1Password-itemet og restart podden.
- `QueueStorageException` med 404: køen `email-delivery-reports` finnes ikke (navnet i
  `communication.email.delivery-reports.queue-name`).
- Nettverksfeil eller timeout: sjekk Azure Storage-status og nettverket ut av clusteret.

## Slå opp en melding

Status for én melding, f.eks. når en konsument spør om en meldings-ID:

```bash
kubectl -n fintlabs-no port-forward deploy/fint-communication-service 8080:8080
curl -s localhost:8080/api/v1/messages/<meldings-ID>
```

Eller direkte i `fint-common`, med forsøk og neste forsøk hvis meldingen fortsatt ligger i køen:

```sql
SELECT m.status, m.failure_reason, m.received_at, m.updated_at, q.attempts, q.next_attempt_at
FROM message m LEFT JOIN dispatch_queue q USING (message_id)
WHERE m.message_id = '<meldings-ID>';
```

Etter `SENT` kan status bli `DELIVERED`, `BOUNCED`, `SUPPRESSED`, `FILTERED_SPAM` eller `FAILED`
(`DELIVERY_FAILED`) fra leveringsrapporten. `updated_at` er da når rapporten ble behandlet. Loggen har
`Leveringsrapport id=<meldings-ID>` med ACS-statusen.

`404` eller ingen rad betyr at ID-en er ukjent, eller at meldingen ble mottatt for mer enn 60 dager
siden og er slettet. Mer om hva som skjedde, finnes i Loki (søk på ID-en).

## Justere grenser

Grensene ligger i `app/src/main/resources/application.yaml` under `communication.limits` og endres med
PR og deploy. En tenant får egne grenser med en override, der nøkkelen er enum-navnet i `Tenant`:

```yaml
communication:
  limits:
    tenant:
      overrides:
        ROGALAND:
          per-hour: 200
          per-day: 1000
```

- Både `per-hour` og `per-day` må settes.
- En tenantgrense kan ikke være høyere enn totalgrensen; da stopper oppstarten.
- Endringen gjelder fra podden er startet på nytt. Forbruket i `send_usage` beholdes, så en senket
  grense kan gi `429` med en gang.
- Utnyttelsen over 1 betyr at forbruket er høyere enn en grense som nettopp er senket.

## Testvarsel

En konstruert overskridelse i beta, for å bekrefte at varslene i Grafana Alerts går:

1. Legg inn en lav override for `NOVARI` i beta med env i `kustomize/overlays/fintlabs-no/beta`, på
   samme måte som log-nivå (se README), og deploy:

   ```yaml
   - op: add
     path: "/spec/env/-"
     value:
       name: "communication.limits.tenant.overrides.NOVARI.per-hour"
       value: "2"
   - op: add
     path: "/spec/env/-"
     value:
       name: "communication.limits.tenant.overrides.NOVARI.per-day"
       value: "2"
   ```

2. Send tre meldinger med `tenant: "NOVARI"` til en testadresse. Den tredje gir `429` med
   `limit: "tenant"`.
3. Bekreft at `communication_limit_rejected_total{limit="tenant", tenant="NOVARI"}` øker, at
   utnyttelsen for `NOVARI` er over 0.8, og at varslene «Tenantgrense nådd» og «Høy utnyttelse, tenant»
   går.
4. Fjern overriden og deploy igjen.

## Verifisere utsending

Bekrefter at e-post går fra REST via mal og ACS til en reell adresse (akseptansekravet i FFS-1968).
Forutsetter at ACS er slått på i miljøet (se [README](../README.md#slå-på-acs)).

1. Åpne en port til tjenesten (den har ingen ingress):

   ```bash
   kubectl -n fintlabs-no port-forward deploy/fint-communication-service 8080:8080
   ```

2. Send testmalen med tenant `NOVARI` til en adresse du har tilgang til:

   ```bash
   curl -i -X POST localhost:8080/api/v1/messages -H 'Content-Type: application/json' -d '{"tenant":"NOVARI","message":{"channel":"EMAIL","to":"<din adresse>","templateId":"novari/test","variables":{"melding":"Verifisering av utsending"}}}'
   ```

3. Bekreft:
   - svaret er `202` med en meldings-ID,
   - `curl -s localhost:8080/api/v1/messages/<meldings-ID>` gir `"status":"SENT"`,
   - loggen har `Melding mottatt` og `Melding sendt` med samme ID, og `status=SENT`,
   - `communication_message_sent_total{tenant="NOVARI"}` har økt,
   - e-posten har kommet fram, med avsender fra `communication.email.sender` og svaradresse
     `no-reply@novari.no`.

`Operation-Id` mot ACS er meldings-ID-en, så et nytt forsøk etter en timeout skal ikke gi to e-poster.
ACS dokumenterer ikke hva som skjer ved gjentatt `Operation-Id`. Første gang loggen viser
`årsak=timeout` fulgt av `Melding sendt` for samme ID, sjekk at mottakeren fikk én e-post, og oppdater
dette avsnittet med resultatet.

## Verifisere leveringsrapporter

Bekrefter at leveringsrapportene fra ACS gir status og blokkering i beta (akseptansekravet i
FFS-2339). Forutsetter at ACS og mottaket av leveringsrapporter er slått på (se
[README](../README.md#slå-på-leveringsrapporter)).

1. Åpne en port til tjenesten som i [Verifisere utsending](#verifisere-utsending).
2. Send testmalen til en adresse du har tilgang til, og til en adresse på et domene som finnes, men
   der adressen ikke gjør det (f.eks. `finnes-ikke-<tilfeldig>@novari.no`):

   ```bash
   curl -i -X POST localhost:8080/api/v1/messages -H 'Content-Type: application/json' -d '{"tenant":"NOVARI","message":{"channel":"EMAIL","to":"<adresse>","templateId":"novari/test","variables":{"melding":"Verifisering av leveringsrapporter"}}}'
   ```

3. Vent et par minutter, og bekreft:
   - `GET /api/v1/messages/<ID>` gir `DELIVERED` for den gyldige adressen og `BOUNCED` for den ugyldige,
   - loggen har `Leveringsrapport id=<ID> acsStatus=Bounced status=BOUNCED … blokkert=ja`,
   - `communication_delivery_report_received_total{tenant="NOVARI", status="BOUNCED"}` har økt,
   - en ny melding til den ugyldige adressen gir `422`,
   - blokkeringen ligger i databasen med `source = 'ACS'` og utløp om 30 dager (hashen beregnes som i
     [driftsrutinen](../README.md#driftsrutine-for-blokkeringslisten)):

     ```sql
     SELECT reason, source, created_at, expires_at FROM recipient_blocklist WHERE recipient_hash = '<hash>';
     ```

4. Fjern blokkeringen etterpå med SQL-en i driftsrutinen.

Det er ikke verifisert hvilket felt ACS fyller i `deliveryAttemptTimeStamp`, eller om Event Grid
legger hendelsen i køen som base64 eller rå JSON (begge leses). Oppdater dette avsnittet med det du ser
første gang.
