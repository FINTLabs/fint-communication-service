# Runbook: grenser, utsending og leveringsrapporter

Runbooken beskriver hva vakthavende gjør når et varsel for fint-communication-service går. Grensene er
beskrevet i [README](../README.md#grenser) og
[arkitekturdokumentet](architecture.md#grenser), utsendingen i [README](../README.md#utsending) og
[arkitekturdokumentet](architecture.md#hva-skjer-med-meldingen-etter-202), og leveringsrapportene i
[README](../README.md#leveringsrapporter) og [arkitekturdokumentet](architecture.md#leveringsrapporter).

## Innhold

1. [Metrikker](#metrikker)
2. [Varselregler](#varselregler)
3. [Første steg ved alle varsler](#første-steg-ved-alle-varsler)
4. [Grense nådd](#grense-nådd)
5. [Meldinger feilet](#meldinger-feilet)
6. [Køen står](#køen-står)
7. [Høy bounce-rate](#høy-bounce-rate)
8. [Mottak av rapporter feiler](#mottak-av-rapporter-feiler)
9. [Uten varsel](#uten-varsel)
10. [Slå opp en melding](#slå-opp-en-melding)
11. [Stoppe e-post til en mottaker](#stoppe-e-post-til-en-mottaker)
12. [Justere grenser](#justere-grenser)
13. [Testvarsel](#testvarsel)
14. [Verifisere utsending](#verifisere-utsending)
15. [Verifisere leveringsrapporter](#verifisere-leveringsrapporter)

## Metrikker

| Metrikk (Prometheus)                              | Type    | Tagger                                                                                                     | Betydning                                                        |
|---------------------------------------------------|---------|------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------|
| `communication_limit_rejected_total`              | Counter | `limit` (`mottaker`, `tenant`, `total`), `tenant`                                                          | Meldinger avvist med 429.                                        |
| `communication_message_accepted_total`            | Counter | `tenant`, `channel`                                                                                        | Meldinger akseptert med 202.                                     |
| `communication_limit_tenant_usage_ratio`          | Gauge   | `tenant`, `window` (`hour`, `day`)                                                                         | Forbruk delt på tenantens grense i glidende vindu.               |
| `communication_limit_total_usage_ratio`           | Gauge   | `window` (`hour`, `day`)                                                                                   | Forbruk delt på totalgrensen i glidende vindu.                   |
| `communication_message_sent_total`                | Counter | `tenant`, `channel`                                                                                        | Meldinger sendt til leverandøren.                                |
| `communication_message_retried_total`             | Counter | `tenant`, `channel`, `reason` (`timeout`, `throttled`, `server-error`, `unauthorized`, `io`, `unexpected`) | Forbigående feil; meldingen prøves igjen.                        |
| `communication_message_failed_total`              | Counter | `tenant`, `channel`, `reason` (`rejected`, `retries-exhausted`)                                            | Meldinger som ikke ble sendt.                                    |
| `communication_dispatch_queue_size`               | Gauge   | –                                                                                                          | Meldinger i køen (ikke sendt eller feilet ennå).                 |
| `communication_dispatch_queue_oldest_seconds`     | Gauge   | –                                                                                                          | Alderen på den eldste meldingen i køen.                          |
| `communication_delivery_report_received_total`    | Counter | `tenant`, `channel`, `template`, `status` (`DELIVERED`, `BOUNCED`, `SUPPRESSED`, `FAILED`, `COMPLAINED`)   | Leveringsrapporter for kjente meldinger, per status i rapporten. |
| `communication_delivery_report_discarded_total`   | Counter | `reason` (`invalid`, `unsupported-type`, `unknown-message`, `poison`)                                      | Rapporter som ble forkastet.                                     |
| `communication_delivery_report_poll_failed_total` | Counter | –                                                                                                          | Feil mot rapportkøen (henting eller sletting).                   |

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

| Varsel                     | Uttrykk                                                                                                                                                                                                                                                  | I minst | Alvorlighet |
|----------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------|-------------|
| Grense nådd                | `sum by (tenant, limit) (increase(communication_limit_rejected_total{$app, limit=~"tenant\|total"}[5m])) > 0`                                                                                                                                            | –       | critical    |
| Meldinger feilet           | `sum by (tenant, reason) (increase(communication_message_failed_total{$app}[15m])) > 0`                                                                                                                                                                  | –       | warning     |
| Køen står                  | `max(communication_dispatch_queue_oldest_seconds{$app}) > 900`                                                                                                                                                                                           | 5m      | critical    |
| Høy bounce-rate            | `sum(increase(communication_delivery_report_received_total{$app, status="BOUNCED"}[1h])) / sum(increase(communication_delivery_report_received_total{$app}[1h])) > 0.05 and sum(increase(communication_delivery_report_received_total{$app}[1h])) >= 20` | –       | warning     |
| Mottak av rapporter feiler | `sum(increase(communication_delivery_report_poll_failed_total{$app}[5m])) > 0`                                                                                                                                                                           | 15m     | warning     |

Varslene dekker bare det som betyr at meldinger ikke blir sendt, eller at avsenderen risikerer å bli
satt på pause hos SES. Resten står på dashboardet og i [Uten varsel](#uten-varsel).

## Første steg ved alle varsler

1. Åpne dashboardet og finn tenanten og tidspunktet.
2. Søk i Loki etter WARN-loggen. Den har grensetype, tenant og meldings-ID, men aldri adresse eller
   hash. Label-navnene avhenger av oppsettet i Loki; i `fintlabs-no` er det typisk:

   ```logql
   {namespace="fintlabs-no", app="fint-communication-service"} |= "Grense overskredet" | json | line_format "{{.message}}"
   ```
3. Se forbruket per tenant og time direkte i databasen `fint-common` (bare siste døgn finnes):

   ```sql
   SELECT tenant, date_trunc('hour', sent_at) AS time, count(*)
   FROM send_usage
   GROUP BY tenant, time
   ORDER BY time DESC, count(*) DESC;
   ```

4. Finn ut hvilken konsument som sender for tenanten. Tenant oppgis i requesten, så inntil
   autentisering er på plass (FFS-1970) kan det være flere applikasjoner bak samme tenant.

## Grense nådd

**Betyr:** meldinger avvises med `429`. Med `limit: "tenant"` gjelder det bare én tenant; med
`limit: "total"` avvises alle tenants. Konsumenten får `Retry-After` og må prøve igjen senere.

**Sannsynlige årsaker:** legitim vekst (ny konsument, ny mal, et fylke med mange varsler), en
konsument med feil (løkke, retry uten backoff) eller en kompromittert konsument.

**Gjør:**

- Finn tenanten som står for forbruket (dashboardet eller SQL-en over). Ved totalgrensen er det ofte én
  tenant som også har nådd sin egen grense. Utnyttelsen på dashboardet viser om det har bygget seg opp
  over tid (legitim vekst) eller kom brått (feil).
- Ved feil eller mistanke om kompromittering: kontakt teamet som eier konsumenten, og vurder å senke
  grensen for tenanten med en override, slik at de andre slipper til.
- Grensen slipper av seg selv når de eldste meldingene glir ut av vinduet (senest etter 1 time for
  timegrensen og 24 timer for døgngrensen). Det trengs ingen opprydning.
- Er trafikken legitim og haster det, [øk grensen](#justere-grenser) for tenanten med en override.
  Totalgrensen holder kostnaden under kontroll; avklar en økning av den med PO.

## Meldinger feilet

**Betyr:** en eller flere meldinger som fikk `202`, ble ikke sendt. Raden i køen er slettet, og
meldingen sendes ikke på nytt av seg selv. Status er `FAILED` med årsak, og konsumenten ser det med
`GET /api/v1/messages/{id}` (se [Slå opp en melding](#slå-opp-en-melding)).

**Finn årsaken:** søk i Loki etter `Melding feilet`. Linjen har meldings-ID, tenant, mal, antall
forsøk, årsak og detalj (feilkode fra leverandøren), aldri adresse eller innhold.

| `reason`            | Betyr                                                                                        | Gjør                                                                                                                             |
|---------------------|----------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------|
| `rejected`          | Leverandøren avviste meldingen, f.eks. ugyldig avsender eller domene som ikke er verifisert. | Gjelder det alle meldinger, er det konfig hos leverandøren. Ellers: se feilkoden.                                                |
| `retries-exhausted` | Alle forsøk (8, over ca. 2 timer) ga forbigående feil.                                       | Se [Køen står](#køen-står): leverandøren har trolig vært utilgjengelig, strupet trafikken eller avvist nøkkelen over lengre tid. |

Meldinger som har feilet, må sendes på nytt av konsumenten. Kontakt teamet som eier konsumenten med
meldings-ID-ene fra loggen.

## Køen står

**Betyr:** den eldste meldingen i køen er over 15 minutter gammel. Meldinger sendes sent eller ikke
i det hele tatt.

**Finn årsaken:**

- `communication_message_retried_total` per `reason` viser hvorfor sendingen feiler:
  - `server-error`, `timeout` eller `io`: leverandøren er utilgjengelig eller treg.
  - `throttled`: leverandøren struper trafikken. Køen tømmes av seg selv når den slipper til igjen.
  - `unauthorized`: nøklene til SES (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`) er feil eller rotert,
    eller AWS har satt sendingen på pause. Sjekk 1Password-itemet og restart podden, og se
    «Reputation metrics» i SES-konsollet.
  - `unexpected` med `detalj=SdkClientException`: nøklene til SES mangler i 1Password-itemet.
  - `unexpected` med `detalj=javax.crypto.AEADBadTagException`: meldingen kan ikke dekrypteres, fordi
    `COMMUNICATION_DISPATCH_ENCRYPTION_KEY` er byttet eller feil. Rettes nøkkelen før forsøkene er brukt
    opp, sendes meldingene; ellers feiler de med `retries-exhausted`.
- I Loki: `Utsending feilet, prøver igjen` (med årsak og feilkode fra leverandøren) og `Kunne ikke hente meldinger fra køen`
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

**Betyr:** mer enn 5 % av leveringsrapportene siste time er `BOUNCED`. SES slutter selv å sende
til adressene, men en høy rate skader omdømmet til avsenderdomenet, og AWS setter kontoen
under vurdering ved 5 % og kan sette sendingen på pause ved 10 % («Reputation metrics» i SES).

**Finn årsaken:**

- `sum by (tenant, template) (increase(communication_delivery_report_received_total{$app, status="BOUNCED"}[1h]))`
  viser hvilken tenant og mal det gjelder. Ofte er det én konsument med en gammel eller feil
  adresseliste.
- I Loki: `Leveringsrapport` med `sesEvent=Bounce/Permanent` gir meldings-ID-ene, uten adresse.
- Kontakt teamet som eier konsumenten. Tjenesten har ikke adressene; konsumenten må finne dem via
  meldings-ID-ene i sitt eget system.

## Mottak av rapporter feiler

**Betyr:** tjenesten har ikke fått kontakt med rapportkøen på 15 minutter. Utsending og mottak av
meldinger virker som før; rapportene blir liggende i køen.

**Finn årsaken:** søk i Loki etter `Kunne ikke hente leveringsrapporter fra køen`. Linjen har bare
unntakstypen.

- `SqsException` med 403 eller `SdkClientException`: nøklene til IAM-brukeren er feil, rotert eller
  mangler tilgang til køen. Sjekk `AWS_ACCESS_KEY_ID` og `AWS_SECRET_ACCESS_KEY` i 1Password-itemet og
  IAM-policyen, og restart podden.
- `QueueDoesNotExistException`: URL-en i `communication.email.delivery-reports.queue-url` er feil.
- Nettverksfeil eller timeout: sjekk AWS Health Dashboard og nettverket ut av clusteret.

## Uten varsel

Disse står på dashboardet og i loggen, men varsles ikke:

- **Høy utnyttelse** (`communication_limit_*_usage_ratio`): «Grense nådd» varsler når det faktisk avvises.
- **Mottaker-429** (`limit="mottaker"`): forventet i små mengder. Mange fra én tenant betyr at
  konsumenten sender ett varsel per hendelse i stedet for en oppsummering, eller ignorerer `429`.
  Kontakt teamet som eier konsumenten; mottakergrensen økes ikke.
- **Klager** (`status="COMPLAINED"`): SES slutter å sende til mottakerne. Klageraten mot SES' terskler
  (0,1 % vurdering, 0,5 % pause) følges i «Reputation metrics» i SES (FFS-2341). Gmail gir ingen klagedata.
- **Forkastede rapporter** (`communication_delivery_report_discarded_total`): `reason="poison"` betyr at
  en rapport ble hentet 5 ganger uten å bli lagret, nesten alltid fordi databasen var nede i over
  20 minutter. Status er da ikke oppdatert. Adressen står likevel på suppression-listen i SES ved en bounce
  eller klage, så det sendes ikke til den igjen. `invalid` og
  `unsupported-type` betyr at configuration set-et publiserer andre hendelser, eller at noe annet enn
  SNS skriver til køen (se [Slå på leveringsrapporter](../README.md#slå-på-leveringsrapporter)).
  `unknown-message` er rapporter for meldinger som er slettet etter 60 dager.

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

Etter `SENT` kan status bli `DELIVERED`, `BOUNCED`, `SUPPRESSED` eller `FAILED` (`DELIVERY_FAILED`
eller `REJECTED`) fra leveringsrapporten. `updated_at` er da når rapporten ble behandlet. Loggen har
`Leveringsrapport id=<meldings-ID>` med SES-hendelsen.

`404` eller ingen rad betyr at ID-en er ukjent, eller at meldingen ble mottatt for mer enn 60 dager
siden og er slettet. Mer om hva som skjedde, finnes i Loki (søk på ID-en).

## Stoppe e-post til en mottaker

Ber noen om å slippe e-post, kontakter du teamet som sendte; teamet fjerner mottakeren i sitt eget
system. Tjenesten har ingen egen blokkeringsliste. Haster det, og teamet ikke kan stoppe utsendingen
raskt nok, legger du adressen på suppression-listen i SES. Kommandoene, også for å fjerne en adresse
igjen, står i [README](../README.md#suppression-i-ses).

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
   varselet «Grense nådd» går.
4. Fjern overriden og deploy igjen.

## Verifisere utsending

Bekrefter at e-post går fra REST via mal og SES til en reell adresse. Forutsetter at SES er slått på
i miljøet (se [README](../README.md#slå-på-ses)). Mens SES-kontoen er i sandkassen, må mottakeren
være verifisert i SES.

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
   - loggen har `Melding mottatt` og `Melding sendt` med samme ID, og `status=SENT`,
   - `GET /api/v1/messages/{id}` gir `SENT`,
   - `communication_message_sent_total{tenant="NOVARI"}` har økt,
   - e-posten har kommet fram, med avsender fra `communication.email.sender` og svaradresse
     `no-reply@novari.no`, uten sporingspiksel eller omskrevne lenker.

## Verifisere leveringsrapporter

Bekrefter at leveringsrapportene fra SES gir status i beta. Forutsetter at SES og
mottaket av leveringsrapporter er slått på (se [README](../README.md#slå-på-leveringsrapporter)).
SES har en egen simulator for testing, som virker også i sandkassen og ikke teller mot bounce-raten.

1. Åpne en port til tjenesten som i [Verifisere utsending](#verifisere-utsending).
2. Send testmalen til simulatoradressene `success@simulator.amazonses.com`,
   `bounce@simulator.amazonses.com`, `complaint@simulator.amazonses.com` og
   `suppressionlist@simulator.amazonses.com`:

   ```bash
   curl -i -X POST localhost:8080/api/v1/messages -H 'Content-Type: application/json' -d '{"tenant":"NOVARI","message":{"channel":"EMAIL","to":"<adresse>","templateId":"novari/test","variables":{"melding":"Verifisering av leveringsrapporter"}}}'
   ```
3. Vent et par minutter, og bekreft:
   - `GET /api/v1/messages/<ID>` gir `DELIVERED` for `success@…`, `BOUNCED` for `bounce@…`,
     `DELIVERED` for `complaint@…` og `SUPPRESSED` for `suppressionlist@…`,
   - loggen har `Leveringsrapport id=<ID> sesEvent=Bounce/Permanent status=BOUNCED` og
     `sesEvent=Complaint`,
   - `communication_delivery_report_received_total{tenant="NOVARI", status="BOUNCED"}` har økt.

Det er ikke verifisert om SNS-abonnementet leverer med eller uten SNS-konvolutt (begge leses).
Oppdater dette avsnittet med det du ser første gang.
