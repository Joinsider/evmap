# 5. Bundesnetzagentur source adapter via the published CSV bulk download

- Status: Accepted
- Date: 2026-07-28
- Deciders: Joinsider

## Context

`SourceAdapter` and `StationIngestionPort` existed, but no adapter implemented them: `SyncJob` logged
"Sync run skipped: no source adapters registered" on every run and the database stayed empty. The
Lastenheft (§5) names the Bundesnetzagentur Ladesäulenregister as the authoritative source for German
locations and the tie-breaker for merge conflicts, so it is the adapter that has to exist first.

Three access paths were investigated; only one is actually open.

**The official REST web service is not self-service.** The Bundesnetzagentur does operate a public
interface to the register in JSON and XML, updated daily. It is not published: the page asks
interested parties to mail `ladesaeulenregister@bnetza.de`, after which they "provide an interface
description in OpenAPI format and inform you of the requirements for use". There is no documented
endpoint, no documented auth scheme, and no way to know the terms before agreeing to them.

**The ArcGIS feature service is closed.** `api.bund.dev` documents the register as an ArcGIS
FeatureServer (`services6.arcgis.com/6jU7RmJig2Wwo1b0/.../Ladesaeulenregister/FeatureServer/7`), which
is what the public Ladesäulenkarte used to consume. It now answers:

```json
{"error":{"code":499,"message":"Token Required","messageCode":"SB_0006"}}
```

for both the service and the layer. That route is gone.

**The CSV bulk download is open and licensed.** The Ladesäulenkarte page links the full register as
CSV and XLSX under CC BY 4.0, attribution "Bundesnetzagentur.de". Verified against the 2026-07-07
edition: 53.114.117 bytes, 113.385 rows.

The download has one property that shapes the whole design — **the file name carries its edition
date**:

```
https://data.bundesnetzagentur.de/Bundesnetzagentur/DE/Fachthemen/ElektrizitaetundGas/
  E-Mobilitaet/Ladesaeulenregister_BNetzA_2026-07-07.csv
```

A hard-coded URL keeps working after a new edition is published — it just silently serves stale data
forever, which is the worst available failure mode.

## Decision

**Ingest the register from the CSV bulk download, discovering the current edition each run.**

### Discovery over a pinned URL

`BnetzaCsvSourceAdapter` fetches the Ladesäulenkarte page and extracts the newest link matching
`Ladesaeulenregister_BNetzA_(\d{4}-\d{2}-\d{2})\.csv`, taking the highest date if several are listed.
`evmap.sync.bnetza.csv-url` overrides discovery entirely — to pin an edition, to point at a local
copy, or to recover if the page layout changes without a code release. When discovery finds nothing,
the adapter fails with a message that names that property, rather than failing obscurely.

The alternative — configuration only — was rejected because it converts a routine upstream event
(a new monthly edition) into silent staleness. Scraping is fragile in a *loud* way; a stale pinned URL
is fragile in a *quiet* way.

### Buffer to a temp file, then stream-parse

The download is written to a temporary file before parsing, and the file is deleted when the parse
stream is closed. Parsing the HTTP response body directly would hold the connection open for the
entire ingestion — which writes to the database as it reads — turning any upstream hiccup into a
half-finished run. 53 MB of scratch disk is the cheaper side of that trade. `Stream.flatMap` closes
each adapter's stream even when the ingestion throws, which is what makes the cleanup reliable.

### A real CSV parser, not a line split

`commons-csv` 1.14.1 is added as the module's first non-Spring third-party dependency. It is not
optional: **13.751 fields in the 2026-07-07 edition contain embedded newlines** (operator names spill
across lines), and quoted fields contain the `;` delimiter itself
(`"RFID-Karte;Onlinezahlungsverfahren"`). A line-oriented split desynchronises on the first such row
and mis-assigns every following column — silently, because the row counts still look plausible.

### Parsing rules, and why each exists

| Property of the file | Handling |
|---|---|
| UTF-8 **with BOM**, CRLF, `;`-separated | Read as UTF-8, BOM stripped per field |
| Ten preamble lines before the header | Header located by its first column `Ladeeinrichtungs-ID`, *not* by skipping a fixed count — the preamble length is not a documented guarantee |
| No per-row timestamp | `lastUpdatedAt` comes from the preamble's `Letzte Aktualisierung vom: dd.MM.yyyy`, falling back to the date in the file name |
| German decimal comma (`48,442398`, `3,7`) | Normalised before parsing; a locale-naive parse would read `48,442398` as an integer or fail |
| Up to 6 Ladepunkte per row, each with a `;`-separated `Steckertypen` cell | Flattened; identical (type, rating) pairs merged into a `quantity` |
| Per-Ladepunkt ratings are a parallel list (`"22; 3,7"`) | Zipped positionally with the plug list; a single rating applies to all plugs of that point; any other length mismatch is dropped rather than guessed |
| `Anzeigename (Karte)` blank in 63.290 of 113.385 rows | Falls back to `Standortbezeichnung`, then `Betreiber` — a blank map label is not acceptable |
| `Status` is `In Betrieb` or `In Wartung` | Mapped to `availability_status` (`OPERATIONAL` / `MAINTENANCE`); an unrecognised or blank status yields `null`, never an assumed "works" |

### Stations under maintenance are ingested, not dropped

An earlier revision skipped every row whose status was not `In Betrieb`, which silently deleted 21
real, registered stations from the map. They are permanently installed infrastructure that happens to
be out of service today, so they belong on the map with their state attached — and that state is
exactly the field Lastenheft §3 asks to be prepared ("Verfügbarkeitsfilter vorbereiten").

The status is stored as a stable token (`sync.AvailabilityStatus`) rather than the register's German
wording, because backend data is monolingual by design while the client renders German and English.
The iOS `AvailabilityStatus` enum maps the tokens to localized strings.

This changed what the client's availability filter had to mean. `MapViewModel` filtered on *having*
a status, which was a workable proxy only while nothing populated the field; with BNetzA reporting a
status for all 113k German stations it would have selected everything. It now filters on the state
being usable.

### Connector vocabulary

The register uses ten distinct German equipment labels. They are normalised in
`sync.ConnectorTypes`, shared with the OCM adapter, onto the closed vocabulary the iOS client filters
against (`ConnectorType.swift`). Ordering matters and is tested: `DC Fahrzeugkupplung Typ Combo 2
(CCS)` must read as CCS and not as Type 2, or a DC fast charger ends up under an AC filter.

`DC Megawatt Charging System (MCS)` (24 charge points) is stored as `MCS` but deliberately has no iOS
enum case — it is truck charging, not a passenger-car plug. It is therefore visible in station detail
and simply never offered as a filter.

## Consequences

### Positive

- Verified end to end against the real 2026-07-07 file: **all 113.385 stations parsed (113.364
  `OPERATIONAL`, 21 `MAINTENANCE`), 214.744 charge points, zero parse failures, zero rows with a
  blank name or invalid coordinates.** Connector totals:
  Type 2 154.704, CCS 52.906, Schuko 3.886, CHAdeMO 3.210, MCS 24, Type 1 9, CEE 4, Tesla 1.
- Works with no credentials and no registration, under a licence the app can satisfy by naming
  "Bundesnetzagentur.de" — which the existing per-station provenance display already does.
- A new edition is picked up automatically; a changed download page fails loudly with a message
  naming the property that recovers it.
- The parser is unit-tested independently of HTTP, and discovery is tested against a fixture of the
  real page markup.

### Negative / accepted risks

- **Monthly data, not daily.** The official web service updates daily; the CSV was last published
  2026-07-07. For a register of installed infrastructure this is acceptable, but it is strictly worse
  than what the official interface offers.
- **Scraping is a coupling to a page layout we do not control.** Mitigated by the override property
  and a loud failure, not eliminated.
- **A full 53 MB download every run**, with no conditional request. The server does send
  `Last-Modified` and `ETag`, so this is improvable but not yet implemented.
- **One new third-party dependency** whose version is ours to track, since the Spring Boot BOM does
  not manage `commons-csv`.

## Open points

1. **A full 53 MB download every run, with no conditional request.** The server sends `Last-Modified`
   and `ETag`, so the adapter could skip the download entirely when the edition has not changed.
   - (a) Keep downloading unconditionally — simplest, and the parse is cheap. *Current behaviour.*
   - (b) Store the edition date as a watermark via `SyncStateStore` (the mechanism already exists for
     OCM) and skip the run when the discovered file name matches.
   - (c) Send `If-Modified-Since` and handle `304`.

2. **Stations removed from the register are not deleted** from `master`. Shared with the OCM adapter
   and a property of the ingestion port rather than of either source; a station absent from two
   consecutive full editions is the obvious signal, but nothing acts on it yet.

## References

- [Bundesnetzagentur Ladesäulenkarte](https://www.bundesnetzagentur.de/DE/Fachthemen/ElektrizitaetundGas/E-Mobilitaet/Ladesaeulenkarte/start.html) — the download page that is scraped
- [Öffentliche Schnittstelle zum Ladesäulenregister](https://www.bundesnetzagentur.de/DE/Fachthemen/ElektrizitaetundGas/E-Mobilitaet/Schnittstellen/start.html) — the official web service, access by mail request
- [ladestationen.api.bund.dev](https://ladestationen.api.bund.dev/) — documents the now token-gated ArcGIS route
- `evmap_service/src/main/java/de/joinside/evmap_service/sync/bnetza/` — the adapter
- `evmap_service/src/main/resources/application-sync.yaml` — `evmap.sync.bnetza.*`
- [ADR 0006](0006-open-charge-map-source-adapter.md) — the other half of the merge
- [ADR 0007](0007-batched-ingestion-transactions.md) — why a 113k-row import no longer runs in one
  transaction
