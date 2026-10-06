# FINT Communication Service

Felles kommunikasjonstjeneste for Novari-plattformen. Applikasjoner og integrasjonsplattformen
sender meldinger hit, og tjenesten står for validering, layout og utsending via ekstern
leverandør. E-post er første kanal; SMS, Slack og webhooks skal kunne legges til uten vesentlig
endring i grunnarkitekturen.

Tjenesten er én multi-tenant deployment i namespace `fintlabs-no`, kun tilgjengelig internt i
clusteret. Tenant er fylket meldingen sendes på vegne av.

## Status

Grunnstruktur: Spring Boot-applikasjon med health-endepunkter, bygg og deploy til beta.
REST API, layout, leverandøradapter og autentisering kommer i egne oppgaver under
[FFS-1865](https://novari-iks.atlassian.net/browse/FFS-1865).

## Endepunkter

| Endepunkt                     | Bruk                    |
|-------------------------------|-------------------------|
| `/actuator/health`            | Startup-probe           |
| `/actuator/health/liveness`   | Liveness-probe          |
| `/actuator/health/readiness`  | Readiness-probe         |

## Lokal utvikling

Krever Java 25.

```bash
./gradlew bootRun
```

```bash
./gradlew check
```

`check` kjører både tester og ktlint.

## Deploy

| Miljø | Cluster                    | Namespace     | Overlay                           |
|-------|----------------------------|---------------|-----------------------------------|
| beta  | `aks-beta-fint-2021-11-23` | `fintlabs-no` | `kustomize/overlays/fintlabs-no/beta` |

Push til `main` bygger image (CI) og deployer til beta (CD). `MD`-workflowen bygger og deployer
manuelt. Produksjon (`api`) er ikke satt opp ennå.
