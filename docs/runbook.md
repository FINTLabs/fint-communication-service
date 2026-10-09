# Runbook: grenser og blokkerte innsendinger

Runbooken beskriver hva vakthavende gjør når et varsel for fint-communication-service går. Grensene og
blokkeringslisten er beskrevet i [README](../README.md#grenser) og
[arkitekturdokumentet](architecture.md#grenser).

## Innhold

1. [Metrikker](#metrikker)
2. [Varselregler](#varselregler)
3. [Første steg ved alle varsler](#første-steg-ved-alle-varsler)
4. [Høy utnyttelse](#høy-utnyttelse)
5. [Tenantgrense nådd](#tenantgrense-nådd)
6. [Totalgrense nådd](#totalgrense-nådd)
7. [Mange mottaker-429](#mange-mottaker-429)
8. [Mange blokkerte innsendinger](#mange-blokkerte-innsendinger)
9. [Justere grenser](#justere-grenser)
10. [Testvarsel](#testvarsel)

## Metrikker

| Metrikk (Prometheus)                     | Type    | Tagger                                            | Betydning                                              |
|------------------------------------------|---------|---------------------------------------------------|--------------------------------------------------------|
| `communication_limit_rejected_total`     | Counter | `limit` (`mottaker`, `tenant`, `total`), `tenant` | Meldinger avvist med 429.                              |
| `communication_blocklist_rejected_total` | Counter | `tenant`                                          | Meldinger avvist med 422 fordi mottakeren er blokkert. |
| `communication_message_accepted_total`   | Counter | `tenant`, `channel`                               | Meldinger akseptert med 202.                           |
| `communication_limit_tenant_usage_ratio` | Gauge   | `tenant`, `window` (`hour`, `day`)                | Forbruk delt på tenantens grense i glidende vindu.     |
| `communication_limit_total_usage_ratio`  | Gauge   | `window` (`hour`, `day`)                          | Forbruk delt på totalgrensen i glidende vindu.         |

Utnyttelsen regnes ut fra `send_usage` hvert minutt (`communication.limits.usage-refresh`), så den kan
ligge opptil ett minutt etter. Hver replika rapporterer samme verdi, så bruk `max`, ikke `sum`, på
tvers av pods. Ingen metrikk har adresse eller hash.

Dashboardet ligger i [`grafana/fint-communication-service.json`](../grafana/fint-communication-service.json).

## Varselregler

Varslene registreres manuelt i Grafana Alerts med metrikkene, tersklene og varighetene under.
Tabellen er kilden: endres en regel i Grafana, oppdateres den her også.
`$app` står for `app="fint-communication-service"`.

| Varsel                       | Uttrykk                                                                                           | I minst | Alvorlighet |
|------------------------------|---------------------------------------------------------------------------------------------------|---------|-------------|
| Høy utnyttelse, tenant       | `max by (tenant, window) (communication_limit_tenant_usage_ratio{$app}) > 0.8`                    | 5m      | warning     |
| Høy utnyttelse, total        | `max by (window) (communication_limit_total_usage_ratio{$app}) > 0.8`                             | 5m      | warning     |
| Tenantgrense nådd            | `sum by (tenant) (increase(communication_limit_rejected_total{$app, limit="tenant"}[5m])) > 0`    | –       | warning     |
| Totalgrense nådd             | `sum (increase(communication_limit_rejected_total{$app, limit="total"}[5m])) > 0`                 | –       | critical    |
| Mange mottaker-429           | `sum by (tenant) (increase(communication_limit_rejected_total{$app, limit="mottaker"}[1h])) > 10` | –       | warning     |
| Mange blokkerte innsendinger | `sum by (tenant) (increase(communication_blocklist_rejected_total{$app}[1h])) > 10`               | –       | warning     |

Alle avvisninger på tenant- og totalgrensen varsles, siden de betyr at meldinger ikke blir sendt.
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
