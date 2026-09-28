# 12. Additional data sources: national charging registers beyond BNetzA and OCM

- Status: Accepted
- Date: 2026-07-29
- Deciders: Johannes Popp

## Context

Coverage today comes from two adapters (ADR 0005, ADR 0006): the Bundesnetzagentur register — authoritative
for Germany, ~113k charge points — and Open Charge Map, community-maintained, crawled for 11 countries and
restricted to `opendata=true` feeds. Outside Germany the map therefore rests entirely on OCM, whose density
varies by country and whose fair usage policy caps how hard the crawl may be pushed.

The question is which further sources add real station coverage for an adapter-sized amount of work.

Two things changed the landscape and shape everything below.

**AFIR Article 20 came into force on 14 April 2025.** Every operator of publicly accessible charge points in
the EU must publish static data (location, connectors, power, opening hours) and dynamic data (availability)
free of charge and without discrimination, routed through the member state's *National Access Point*. Static
data must be refreshed at least every 24 h. This is why several countries now have a BNetzA-shaped
national register that did not exist when the OCM adapter was written — and why more will appear. New
countries are becoming incremental instances of one adapter shape rather than one-off integrations.

**The UK went the other way.** The National Chargepoint Registry — free, OGL-licensed, ~63k chargepoints —
was decommissioned on 28 November 2024. DfT's replacement provider is Zapmap, delivering through its
commercial Insights product. There is no free UK bulk source any more.

### What was verified live on 2026-07-29

| Source | Endpoint reachable without key | Volume | Format |
|---|---|---|---|
| France, consolidated IRVE | yes, HTTP 200, 162 MB | **231.647 charge points / 64.251 stations** | CSV |
| Switzerland, `ch.bfe.ladestellen-elektromobilitaet` | yes, HTTP 200, 26 MB unzipped | **19.065 EVSEs / 38 operators** | OICP JSON, gzip |
| Switzerland, status feed | yes, HTTP 200 | per-EVSE `EVSEStatus` | OICP JSON, gzip |
| OpenStreetMap via Overpass | yes, HTTP 200 | worldwide | JSON |
| `openchargemap/ocm-data` (GitHub) | repo present but **archived-in-effect** | last push 2021-11-05, described `[deprecated]` | — |

France alone is roughly twice the German register.

Connector vocabularies were checked against the existing `ConnectorTypes.normalize()`:

- **Switzerland** exposes 9 distinct plug labels. Seven map correctly today (`Type 2 Outlet` → `Type 2`,
  `CCS Combo 2 Plug (Cable Attached)` → `CCS`, `Tesla Connector` → `Tesla`, …). Two do not:
  `Type J Swiss Standard` (9 occurrences) and `Type G British Standard` (1). They fall through to the raw
  label, which is the intended degradation and not worth a vocabulary change.
- **France** does not use free text at all: connectors are five boolean columns
  (`prise_type_2` 149.910, `prise_type_combo_ccs` 75.186, `prise_type_ef` 61.200, `prise_type_chademo` 12.185,
  `prise_type_autre` 4.069). That is a structured mapping in the adapter, not a `normalize()` call —
  `prise_type_ef` is the Schuko/domestic socket, `prise_type_autre` carries no type information and should
  produce no connector rather than a junk one.

Both sources also carry an access-restriction signal that has to be honoured the way ADR 0006 handles OCM's
status ids: Switzerland reports `Restricted access` (4.764 EVSEs) and `Test Station` (2) alongside the
publicly accessible ones; France has `condition_acces` = `Accès réservé`. Ingesting those unchanged would put
chargers on the map that a driver cannot use.

## Decision

**Add national-register adapters in coverage-per-effort order, France first. Do not pursue the UK, Italy, or
an OCM mirror.**

Charge points the source itself marks as not publicly usable are skipped rather than ingested, in every
adapter below — see "Resolved" point 2.

### Tier 1 — recommended, no blocking prerequisite

**France — consolidated IRVE file (Etalab / data.gouv.fr).** The closest analogue to the BNetzA adapter that
exists: a plain daily CSV over HTTPS, no key, `Licence Ouverte 1.0` (attribution only, commercially usable).
One row per charge point with `id_station_itinerance` as the station grouping key, `coordonneesXY`,
`puissance_nominale`, operator and `date_maj` for per-field provenance. Largest single coverage gain
available, and it lands in a country the OCM crawl already covers only thinly.

**Switzerland — `ich-tanke-strom` / DIEMO (BFE, via `data.geo.admin.ch`).** One gzipped OICP JSON, no key,
continuously updated, operators nested above `EVSEDataRecord`. Small file, small adapter. The separate status
feed maps cleanly onto `AvailabilityStatus` at sync time — note it reported `Unknown` for the sampled records,
so treat it as best-effort, and real-time availability remains a v1 non-goal (Lastenheft §10) either way.

### Tier 2 — recommended, but gated on a human registration step

Each of these is a legally mandated national register, i.e. authoritative for its country the way BNetzA is
for Germany. Each needs a key obtained by hand, exactly like the OCM key — so each must follow ADR 0006's
rule that a blank key skips the adapter with a warning instead of failing the run.

- **Austria — E-Control Ladestellenverzeichnis (`ladestellen.at`).** Operators are obliged by law to report
  every charge point including ad-hoc prices and live status. Free API, self-service registration at
  `admin.ladestellen.at`. Authoritative replacement for OCM's Austrian data.
- **Netherlands — DOT-NL, the Dutch AFIR National Access Point (NDW).** Publishes OCPI and GeoJSON, free.
  NDW's own documentation splits supplier and consumer flows; the consumer terms need reading before
  committing.
- **Poland — EIPA (UDT).** Six JSON files (`station.json`, `point.json`, `dictionary.json`, `operator.json`,
  `pool.json`, `dynamic.json`). Registration required, hourly download quota, **licence not stated** — that
  needs clarifying with UDT before ingestion, not after.
- **Norway and Sweden — NOBIL (Enova).** De-facto register for both, free API key after manual approval.
  Adds two countries the OCM crawl does not cover at all. Note it dropped Denmark, Finland and Iceland in
  October 2025, so it is a two-country source now, not a Nordic one.

### Tier 3 — gap filler, licence question first

**OpenStreetMap via Overpass.** Verified working without a key; `amenity=charging_station` nodes carry
`socket:type2`, `socket:*:output`, `capacity`, `operator`. Worldwide, which is exactly what the countries
with no register need. The blocker is not technical: OSM is **ODbL**, a share-alike licence, which is a
different obligation from the attribution-only licences every current source uses. Tag completeness also
varies far more than a register's.

### Not pursued

- **United Kingdom.** NCR decommissioned 2024-11-28; the DfT successor is a commercial Zapmap product. OCM
  and OSM stay the only free UK routes.
- **Italy — Piattaforma Unica Nazionale (PUN).** Ministerial platform, but map and list views only; no raw
  open-data export at the time of writing. Worth re-checking, since AFIR obliges Italy to run a NAP.
- **Belgium — MOW Vlaanderen WFS.** Real and open, but Flanders only, and sourced from Eco-Movement, which
  also feeds the French consolidation — meaning much of it arrives through data we would already have.
  Wallonia has no equivalent publication.
- **Eco-Movement and comparable aggregators.** Commercial, and already upstream of several of the public
  files above.
- **Mirroring the OCM dataset.** ADR 0006 open point 2 assumed cloning OCM's published GitHub dataset as the
  route to wider coverage. That route is gone: `openchargemap/ocm-data` is labelled `[deprecated]` and has
  not been pushed to since 2021-11-05. Widening OCM now means more live crawling against the fair usage
  policy — which strengthens the case for national registers instead.

## Consequences

### Positive

- France and Switzerland together roughly triple the non-German station count without a single API key,
  without a rate-limit negotiation, and without touching the OCM crawl budget.
- Both are official or officially commissioned registers, so they outrank OCM for their countries in the
  merge the way BNetzA does for Germany — the same "authoritative source wins ties" rule, applied per
  country instead of only to DE.
- The existing seams hold. `SourceAdapter` + `SourceStation` + `StationIngestionPort` need no change; a new
  adapter is a new sub-package under `sync`, its own `evmap.sync.<source>` config block, and vocabulary
  mapping. `ConnectorTypes.normalize()` already covers Switzerland's labels.
- AFIR makes this repeatable rather than a series of special cases: every EU member state must run a NAP
  publishing OCPI- or DATEX II-shaped data on a 24 h refresh. An OCPI-reading adapter written for the
  Netherlands is reusable for the next OCPI NAP.

### Negative / accepted risks

- **Every new country is also a new vocabulary and provenance surface.** France's boolean connector columns
  and Switzerland's `Type J Swiss Standard` are the mild cases; a register whose labels do not fit gets
  stored as unfilterable raw text.
- **Per-country deduplication pressure grows.** Each register overlaps the OCM data already ingested for that
  country, so the geo-distance + address matching in `PostgresStationIngestionRepository` faces far more
  near-duplicates than it does today. This is the main risk in the plan and should be measured on the French
  import before Tier 2 is started.
- **The French file is 162 MB and grows.** It has to stream; loading it in memory is not an option. The
  batched ingestion (ADR 0007) already handles the commit side.
- **Swiss licensing is not attribution-only.** opendata.swiss classifies it `terms_by_ask`: free use,
  attribution mandatory, **commercial use requires the data supplier's permission**. Fine for the app as it
  stands; it becomes a question the moment EVMap is monetised.
- **Tier 2 sources cannot be enabled by a deploy alone** — each needs a human to register first.

## Resolved on 2026-07-29

1. **France first.** One adapter, the largest coverage gain, and it puts the deduplication risk under
   measurement on the hardest case before any further register is added. Switzerland follows once the
   French import has shown what the overlap with the existing OCM data actually costs.

2. **Restricted-access charge points are not ingested.** `Accès réservé` (FR) and `Restricted access` /
   `Test Station` (CH) are skipped in the adapter, the same way ADR 0006 skips planned, decommissioned and
   duplicate OCM listings: a charge point a driver cannot use does not belong on the map, and this keeps the
   schema and the iOS client unchanged. The count of skipped records is worth logging per run — if it turns
   out to be a large share of a country, option (b) from the original alternatives (an access field plus an
   iOS filter) becomes worth revisiting.

3. **The Swiss `terms_by_ask` licence is accepted as is.** Attribution is already satisfied by the existing
   per-station source display. Commercial use requires the data supplier's permission and the app is not
   monetised — this therefore becomes a question at monetisation, not now, and should be re-raised then
   rather than silently inherited.

## Implemented on 2026-07-29: the French adapter (`sync.irve`)

Built as decided, and verified end to end against the live 2026-07-29 consolidation. **51.082 French
stations are emitted** from 230.558 charge points — roughly the size of the German register, from a
single daily CSV and no API key.

Four things about the file drove the design, and all four were found by running the parser over the
real 162 MB rather than by reading the schema:

**Rows of one station are not adjacent.** 48.080 of the 64.251 stations are re-entered later in the
file. Grouping therefore has to be a map, not a running comparison against the previous row — an
adjacent-only grouping would have emitted the same station several times, and since ingestion is an
upsert keyed on `(source, sourceStationId)`, each later emission would have *overwritten* the
connectors of the earlier one. The map costs ~62 MB of heap for the whole country and the file is
parsed in about 1,5 s; stations are then handed out one at a time, so 64.000 finished `SourceStation`s
are never held at once.

**The id columns sometimes contain prose.** `id_station_itinerance` holds `"Non concerné"` on 1.192
rows from unrelated operators across France. Grouping on it merged **369 separate locations into one
station**, which then took the coordinates of whichever row was read first — a station in the middle of
the country aggregating chargers from Lyon to Lille. `id_station_local` is worse: 589 rows say
`"Non renseigné"` and three more spellings of `"Non concerné"` appear. The rule adopted is that an
identifier contains no whitespace — verified to reject exactly the placeholders and nothing else,
and chosen over matching the phrases so the next publisher's wording does not slip through. A row
whose id columns yield nothing usable is skipped: a station that cannot be keyed stably would be
re-created rather than updated on every run.

**The same rating is spelled several ways.** `22`, `22.0` and `22.00` all occur, and
`BigDecimal.equals` compares scale as well as value — so as the key that merges plugs into quantities
they were three distinct keys. One real station came out with `Type 2 @ 22 ×213`, `Type 2 @ 22.00 ×6`
and `Type 2 @ 22.0 ×1` side by side. Normalising the scale removed 7.589 spurious connector rows
(106.305 → 98.716) without changing the charge-point count.

**Ratings are sometimes stated in watts.** 769 rows say `7360`, 90 say `3680`, with a tail to
`160000`. Values above 1.000 are read as watts. The accepted edge: a genuine MCS rating of 1.200 kW
would be misread as 1,2 kW. There is no MCS in the French file today, and silently advertising 7.360 kW
would be the worse failure.

Beyond those: booleans arrive in all eight spellings (`true/True/TRUE/1` and their negatives);
`condition_acces` arrives in five distinct mojibake encodings of `"Accès libre"`, so the restriction is
matched on the accent-folded stem rather than by equality; `consolidated_code_postal` and
`consolidated_commune` are filled for only 58 % and 65 % of rows, so both are recovered from the
address line, which yields a postal code for 97 %; and the consolidated schema publishes **no
operational status**, so `availabilityStatus` is `null` rather than an invented `OPERATIONAL`.

Restricted charge points are skipped per the decision above: 13.265 stations have no publicly
accessible charge point and are not ingested, while the 726 stations that mix both kinds are kept,
since a driver can genuinely use them.

The adapter itself is small — resolve URL, download, parse — because there is no discovery step: the
data.gouv.fr resource id is stable and redirects to the current date-stamped edition. The download and
temp-file handling moved into `sync.support.BulkDownload`, shared with BNetzA; see ADR 0013 for the
sync-package restructuring this went in with.

## Open points

4. **OpenStreetMap / ODbL.** Options: (a) leave OSM out entirely and accept blank countries; (b) ingest it,
   flag those stations by source, and carry the ODbL attribution and share-alike obligations in the app;
   (c) defer until a country actually matters enough to justify the licence work.

5. **Whether to model per-country source precedence explicitly.** Today "BNetzA wins ties for German
   locations" is a rule about one source. With four or five national registers it becomes a table —
   authoritative source per country code — and that is worth introducing before the third register, not
   after.

6. **Poland's licence.** Unstated on the EIPA documentation. Needs an answer from UDT before any ingestion.

7. **How much the French data overlaps what OCM already delivered for France — partly answered.**
   The first real ingestion into a populated database reported **33.286 created and 17.796 updated,
   with zero unchanged**, in 44 seconds. Since the database already held OCM's French sites and this
   was the adapter's first run, the 17.796 are French stations the geo-deduplication matched to
   records already known from OCM rather than duplicating — roughly 35 %, which is the reassuring
   direction: the merge is doing its job and France still contributes ~33.000 genuinely new stations.
   <br><br>
   What this single run cannot show is whether the matching is *stable*. The clean confirmation is a
   second consecutive run: it should report almost entirely `updated`/`unchanged` and close to zero
   `created`. If it instead creates thousands again, the merge is matching differently from run to run
   and France is accumulating duplicates. Worth doing before Switzerland is added — it is one more run
   and it reads straight off the per-source summary line.
   <br><br>
   Also worth a look while there: BNetzA and IRVE both report **zero `unchanged`** while OCM reports
   15.469. For BNetzA that is explained — every row carries the file's edition date, so a new edition
   makes every station look modified — but for IRVE, whose `date_maj` is per row and stable, it is not,
   and it may mean the change detection compares something that always differs. Pre-existing behaviour
   rather than something this adapter introduced, but it makes `updated` counts less informative than
   they look.

## References

- [Fichier consolidé des IRVE — transport.data.gouv.fr](https://transport.data.gouv.fr/datasets/fichier-consolide-des-bornes-de-recharge-pour-vehicules-electriques)
  — CSV `https://www.data.gouv.fr/api/1/datasets/r/eb76d20a-8501-400e-b336-d85724de5435`, GeoJSON
  `.../7eee8f09-5d1b-4f48-a304-5e99e8da1e26`
- [Ladestationen für Elektroautos — opendata.swiss](https://opendata.swiss/de/dataset/ladestationen-fuer-elektroautos)
  — data `https://data.geo.admin.ch/ch.bfe.ladestellen-elektromobilitaet/data/oicp/ch.bfe.ladestellen-elektromobilitaet.json`,
  status `.../status/oicp/...`
- [SFOE/ichtankestrom_Documentation](https://github.com/SFOE/ichtankestrom_Documentation) — Swiss feed docs
- [E-Control Ladestellenverzeichnis API — data.gv.at](https://www.data.gv.at/katalog/en/dataset/ef680c5f-fa70-4719-8253-e3a5fe5f9355),
  registration at `https://admin.ladestellen.at/#/api/registrieren`
- [DOT-NL / Laadpunten API — NDW](https://docs.ndw.nu/data-uitwisseling/interface-beschrijvingen/dafne-api/)
- [EIPA — UDT](https://eipa.udt.gov.pl/reader/docs)
- [NOBIL API documentation](https://info.nobil.no/files/API_NOBIL_Documentation_v3_20250827.pdf) and the
  [Denmark/Finland/Iceland withdrawal notice](https://info.nobil.no/nyheter/264-important-update-on-nobil-data-nobil-will-no-longer-support-data-from-denmark-finland-and-iceland)
- [AFIR Article 20 / National Access Points](https://alternative-fuels-observatory.ec.europa.eu/) — the
  obligation driving new national registers
- [NCR decommissioning](https://www.zapmap.com/for-business/ncr-decommission) and the
  [DfT/Zapmap successor contract](https://www.zapmap.com/news/dft-awards-zapmap-contract-deliver-electric-vehicle-chargepoint-open-data)
- [ADR 0005](0005-bundesnetzagentur-source-adapter.md) — the register-shaped adapter these would follow
- [ADR 0006](0006-open-charge-map-source-adapter.md) — the OCM adapter, whose open point 2 this ADR closes
  negatively
- [ADR 0007](0007-batched-ingestion-transactions.md) — the batched ingestion that makes a 162 MB import safe
- [ADR 0013](0013-per-source-isolation-in-the-sync-run.md) — the sync restructuring the French adapter
  went in with
- `evmap_service/src/main/java/de/joinside/evmap_service/sync/irve/` — the adapter
- `evmap_service/src/main/resources/application-sync.yaml` — `evmap.sync.irve.*`
