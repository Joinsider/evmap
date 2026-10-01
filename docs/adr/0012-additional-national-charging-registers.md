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
  `admin.ladestellen.at`. Authoritative replacement for OCM's Austrian data. **Skipped on 2026-09-30:
  the terms of use forbid what this service does with data — see "Austria skipped".**
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
  Re-checked on 2026-09-30 and skipped, see "Italy skipped".
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

## Austria skipped on 2026-09-30

The roadmap's first gap filler (L1) was to be the E-Control register. The API documentation sits behind
the registration, so the owner registered and supplied the terms of use before any code was written. They
rule the source out for this architecture, and the decision is to **skip Austria and leave it to OCM**.

What the terms say (Nutzungsbedingungen, Ladestellenverzeichnis API) and where it collides with EVMap:

- **§7, copying and onward transmission.** Copying the data, or any part of it, is allowed only within
  the statutory free use of a work; passing on the collected data "z. B. als Datei oder das
  Weitervermitteln als Webservice" is not permitted. EVMap stores sources in `master.*` and serves them
  through its own public REST API, which is exactly that.
- **§3 i), no modification.** Every value must be shown as the API delivered it. `ConnectorTypes`
  and `AvailabilityStatus` normalization, the geo/address deduplication and the merge with OCM all change
  values.
- **§3 g), usage reporting.** Unique visits and unique visitors must be reported to E-Control every
  quarter. EVMap measures neither, and counting visitors would run against ADR 0002.
- **§3 c)–e), attribution.** The E-Control logo as an image link, "Datenquelle: E-Control" directly at the
  data and a fixed disclaimer. Manageable on their own.
- **§9, penalty.** €10.000 per breach of points 1–5, independent of fault; §3 i) is in that range.
- **§10, revocation.** E-Control may withdraw the permission with three months' notice and no reason.
- **§4, limits.** 2.500 requests per hour and 30 concurrent for all users. Not a problem.

Conflicting licence statements exist: a data.gv.at listing is reported to say CC BY 4.0 while the
terms above apply to the key. That could not be verified (the listing was not reachable), and the
terms are what a registration binds the user to.

**Alternative considered and rejected: a live pass-through module** in the style of `availability` —
queried by bounding box, unmodified, attributed, not stored, not merged. It would be compliant in
letter but: the API shape is unknown (the one public client uses `/search` by coordinate, 10 results);
the `§7` "Webservice" question stays open for a backend that relays the data to its own clients; and
Austrian stations would be second-class — no connector/power filter, no comments, favorites or prices
(they hang off `master.*` ids), and absent from the `along-route` query of phase 4, which is a PostGIS
query over `master.*`. The effort would be closer to L than S–M.

**Other Austrian routes, looked at on 2026-09-30 and not pursued now** (from catalogue pages; none
of the terms could be read):

- **ÖAMTC E-Ladestationen** (listed on mobilitaetsdaten.gv.at): REST + OAuth2, JSON, 10-minute updates,
  Austria-wide including availability. "Lizenz mit Nutzungsgebühr": free for approved purposes, annual SLA
  contribution, sample contract and API documentation as PDFs on the catalogue page. The only candidate
  worth a contract read; whether storing, normalizing and relaying through our API is allowed is unknown.
- **OpenStreetMap / Overpass:** ODbL share-alike would apply to the merged `master.*` (dedup makes it a
  derivative database) and sits badly with the Swiss `terms_by_ask` licence. Belongs to open point 4, not
  to Austria.
- **Operators directly** (Wien Energie, Smatrics, Energie Steiermark, …): Austria's national access point is
  only a catalogue; no open bulk feed found. **Aggregators** (Eco-Movement, TomTom): commercial, TomTom
  already rejected in ADR 0015.
- Before any of this, measure what OCM already delivers:
  `SELECT count(*) FROM master.charging_station WHERE country_code = 'AT'`.

**What would reopen it:** written permission from E-Control to store, normalize and merge the data, or
a dataset under an open licence (CC BY 4.0) via the national access point for AFIR
(mobilitaetsdaten.gv.at). Either brings it back as an ordinary Tier 2 adapter (blank key skips it, per
ADR 0006).

OCM keeps covering Austria (`AT` is in its default country list), so the map is not blank there. The
owner has not registered for the key, so no 👤 step is open for this.

## Switzerland (L2): data findings and decisions, 2026-09-30

Profiled on the live BFE/DIEMO file before writing the adapter (1,0 MB gzip, 27 MB JSON, 41 operators,
**19.478 EVSE records**, one record per EVSE, not per station):

- `ChargingStationId` yields 13.658 groups, the coordinates only 8.994; up to 75 EVSEs with distinct
  station ids share one point, and 10.449 records use their own EVSE-ID as station id. The adapter therefore
  clusters by position and emits one `SourceStation` per cluster. Emitting per station id would
  let the ingestion's 30 m match hand every EVSE of a site to one station, each overwriting the previous
  one's charge points — the failure ADR 0012 already recorded for IRVE.
- `Restricted access` (4.914) and `Test Station` (2) are skipped per the 2026-07-29 decision, leaving 14.562
  public EVSEs.
- 168 records carry the placeholder coordinate `50.0, -15.0` (Atlantic); a few are real stations in
  Germany or Austria. Anything outside Switzerland and Liechtenstein is dropped and counted.
- Five EVSE-IDs appear under two operators with conflicting access; the publicly accessible one is kept.
- Two records hold 157 identical plugs on one EVSE — a data error. In the current edition both are also
  listed by a second operator with sane data, and the first listing wins, so the guard never fires; it stays
  as a rule (more than eight plugs on one EVSE → one connector per distinct type and rating) and is tested.
- Power is `0` on 709 and missing on 487 facilities; both mean "unknown", never 0 kW.
- Plug labels map onto `ConnectorTypes` except `Type J Swiss Standard` (9) and `Type G British Standard` (1).
- The separate status feed is **not** ingested: it is live occupancy and ADR 0015 keeps that out of
  `master.*`. `availabilityStatus` stays `null`, which supersedes the Tier 1 remark above. The register's
  EVSE-IDs are kept, so the MobiData availability join gets exact matches for Switzerland.
- The server stores the document gzipped and answers `Content-Encoding: gzip`. The JDK HTTP client does not
  undo that label, another client would; `BulkDownload` now recognises gzip by its magic bytes and copes
  with both.

**Implemented as `sync.ch` (`DiemoSourceAdapter`, `DiemoOicpParser`, source token `DIEMO`).** Run against the
live file on 2026-09-30: **4.977 stations from 14.394 public EVSEs** (of 19.478 records), skipping 4.916
restricted or test, 166 outside the country and 2 repeated EVSE-IDs (three more repeats were restricted
listings). Every station id is unique and the closest two stations are 35,06 m apart. 682 connectors have no
rating. Download 0,7 s, parse 0,4 s. Clustering is at 35 m from the first EVSE of a site, in a fixed
south-to-north order so the same file yields the same stations. The first of two public listings of one
EVSE-ID wins, which depends on the operator order in the file — accepted, it affects two EVSEs.

Accepted edges: stations of neighbouring countries that fall inside the country envelope keep country `CH`
(the register labels everything `CHE`), so the ingestion cannot match them to the German or Austrian
record of the same site; 219 postal codes have five digits, some of them foreign. The station name is the
first EVSE's name and is often an internal label (`Parkplatz 4`); the street stands in when there is none.

**Decision (open point 5): a configurable authority table per country, implemented as `SourceAuthority`.** `evmap.sync.authority` maps
country code to source — `DE: BNetzA`, `FR: IRVE`, `CH: DIEMO`. A source that is not the country's
authority no longer overwrites a station's fields or inventory once the authority owns it; it is only
linked. The authority takes over a station an other source created first. This replaces the hard-coded
BNetzA rule and ends the order dependence found in `PostgresStationIngestionRepository`: adapters have no
defined order (`SyncJob` takes them as the component scan delivers them), and for every country but
Germany the source that ran last replaced station fields, charge points and EVSE-IDs — so OCM running
after IRVE or DIEMO would drop EVSE-IDs on matched stations. Read from the code, not measured on
production data.

The rule as built: the authority may always rewrite a station, which is how it takes over one another source
created first; any other source may rewrite it only while the authority has no `station_source` row for it.
Deliberately weaker than the old German rule, which froze every German station for non-BNetzA sources even
when BNetzA had never matched it — those stations now keep being maintained by their own source. `LI` is
listed next to `CH` because the register carries Liechtenstein's one station.

## First production runs, 2026-09-28 to 09-30: charge point ids shared between IRVE stations

The run history of the production sync shows every run "Unvollständig" from 2026-09-28 23:39, the first that
ingested IRVE, with 376, 403, 404 and 405 failed stations — the same stations each run, before the Swiss
adapter and the authority table existed. Every failure was `duplicate key … charge_point_source_source_charge_point_id_key`.

**Cause, checked against the 2026-09-30 file (157.561.403 bytes, the size the sync downloaded):**
**20.742 `id_pdc_itinerance` values are listed under more than one station** (20.713 under two, 29 under
three) — publishers describing one charge point as two entries, e.g. "HYPER U - Rumilly" and "ABB T360 HyperU
Rumilly 1" both list `FRSWSE10001499862`. `master.charge_point` allows an id on one row only. When the
stations are within 30 m the ingestion joins them into one master station and the second write simply
replaces the first (88 % of the 3.965 station pairs); when they are not (476 pairs, median 523 m) the second
station fails every run. 400 of the 405 failed stations hold such an id, 399 of the 404 conflicting keys are
listed under several stations. The unexplained five are stations the check could not model. An earlier reading
of the log, that it heals itself on the next run, was wrong: the run history refutes it.

**Decision (owner, 2026-09-30): the station whose own id the charge point id extends keeps it**, else the first
in file order. `IrveCsvParser.owners` resolves it over the stations that are emitted (a restricted station
cannot take an id from a public one); `extendsStationId` encodes the French scheme (`FRMELPINT5910001` →
`FRMELEINT591000121`, `E` for `P`, the `FR` prefix optional). Every other station keeps the plug but not the
id: its charge point gets no EVSE-ID and the key `<station id>*shared*<charge point id>`, derived and therefore
stable, and outside the positional `<station id>*<n>` keys. Plug counts are unchanged (263.665 on the real
file); what is lost is only a second station's claim on the live availability join.

Against the real file the parser now emits 52.832 stations and 166.466 charge points with **no repeated key
and no repeated EVSE-ID**; 16.492 ids are shared, 9.718 of them decided by the naming scheme, the rest by file
order. The summary line logs both counts.

Known residue: if a shared id changes owner between two editions, the new owner can be written before the old
one released it and fail once (the old owner's write then frees it). The owner is decided by ids and file
order, so this needs a publisher to change a station id. Making `replaceInventory` release an id held by
another station of the same source would remove it, and was left out as unneeded until it shows in a run.

## Italy skipped on 2026-10-01

The roadmap's third gap filler (L3) was the Italian register, PUN (Piattaforma Unica Nazionale, GSE on behalf
of MASE, ~67.000–70.000 EVSEs). The decision is to **skip Italy and leave it to OCM**; `IT` is already in the
default `OCM_COUNTRY_CODES`.

What was found on 2026-09-30 (desk research; the PUN API was not called):

- **No open bulk export any more.** PUN used to publish a signed national CSV on S3, daily at 04:30 UTC. AgID's
  `cruscotto-italia` ETL (`etl/sources/pun.py`) records that GSE disabled the "Esporta dati" button and the S3
  endpoint in June 2026.
- **What remains is the portal's own API** (`api.pun.piattaformaunicanazionale.it`): anonymous AWS Cognito guest
  credentials, whose identity pool id is published in the portal's `config.json`, then a paged map search for
  the EVSE-IDs and `chargepoints/group` in batches of 100 for the details, about 700 calls per run. It is
  undocumented and is the web portal's interface, not a published data service.
- **Licence is an assumption.** CC BY 4.0 with attribution to GSE is what AgID derives from "open data by
  default" (Art. 52(2) CAD, AgID guidelines, Determinazione 183/2023). GSE states no licence on the data itself.
- **Owner finding, 2026-10-01: access to PUN is limited to Italians with an Italian identity document.**
  That ends the question for a service run from outside Italy, whatever the guest-credential route in AgID's
  code suggests. Not verified independently.
- Italy's AFIR National Access Point was not found as an open dataset; no other open Italian source turned up.
  `carburanti.mise.gov.it` is the fuel-price portal, not a charge point source.

An adapter against the portal API was considered and rejected: undocumented, GSE has just closed the official
export, the licence is unconfirmed, and the access itself is restricted.

**What would reopen it:** an official, documented export or API of PUN open to foreign users with a stated
licence that allows storing, normalizing and relaying, or an open dataset (CC BY 4.0) via Italy's National
Access Point (AFIR). It would come back as an ordinary adapter: `sync.it`, source token `PUN`, `ITA: PUN`
in `evmap.sync.authority` (without the entry OCM would overwrite its EVSE-IDs), and an extended
`SourceAdapterRegistrationTests`. No 👤 step is open for this.

## Spain (L4): source check and decisions, 2026-10-01

The fourth gap filler is the Spanish register. Checked live on 2026-10-01 before any code was written. Note
that Spain is **not** in the default `OCM_COUNTRY_CODES` (`DE,AT,CH,NL,BE,LU,FR,IT,DK,PL,CZ`), so until this
adapter, Spain had no coverage at all.

**Source.** The DGT's National Access Point (`nap.dgt.es`, ESNAP) publishes the register that MITERD keeps
under Order TED/445/2023 (operators of publicly accessible charge points must report to it): DATEX II v3,
`https://nap.dgt.es/datex2/v3/miterd/EnergyInfrastructureTablePublication/electrolineras.xml`. No key and no
registration; 83 MB downloaded in 7 s; refreshed every 24 h. The dataset page states **CC-BY**. Not used:
MITERD's open-data catalogue (no charge point download found) and Red Eléctrica's `mapareve.es` (a viewer).

**Licence — decision (owner, 2026-10-01): take the dataset's CC-BY as stated.** The DGT's general legal
notice (`dgt.es/contenido/aviso-legal`) says unauthorised reproduction, distribution, commercialisation or
transformation of its works is an infringement. It speaks of the portal's design and code and does not
mention the datasets, and it does not contradict the CC-BY on the dataset page, but it was not clarified
with the DGT either. Accepted residual risk; if the DGT objects, the adapter goes off with `MITERD_ENABLED=false`
and Spain is blank again. Attribution "DGT / MITERD" is the per-station source display.

**What the file holds** (2026-10-01 edition): 12.037 sites, one `energyInfrastructureStation` each, 35.546
charge points (`ElectricChargingPoint`) with 42.686 connectors, 156 operators (Endesa X Way 2.821 sites,
Iberdrola 2.736, Repsol 1.860). All coordinates are inside Spain; no duplicate site id, charge point id or
EVSE name.

- **No access restriction.** `fac:accessibility` is empty on every site. The register covers public points only,
  so nothing is skipped on that account (decision 2 of 2026-07-29 has nothing to act on).
- **No operational status.** `availabilityStatus` stays `null`, as in IRVE and DIEMO. There is no live feed in
  this file; the separate dynamic data (price, availability) is out of scope (ADR 0015).
- **Connectors** are DATEX enumerations and map explicitly: `iec62196T2` → Type 2 (27.212), `iec62196T2COMBO` and
  `iec62196T1COMBO` → CCS (10.367 / 6), `chademo` (3.728), `domesticF` → Schuko (1.169), `iec60309x2single16` and
  `iec60309x2three32` → CEE, `iec62196T1` → Type 1 (54). `domesticA/E/L` and `iec62196T3A/C` (14 in total) become
  readable raw labels, the intended degradation.
- **Power** is in watts (`maxPowerAtSocket`), always present, 2.069 connectors above 150 kW, four CCS at 480–1.000 kW
  (kept). Six read `60` W, which is not a charger: below 1 kW is "unknown", never a rating.
- **The EVSE-ID is the charge point's `fac:name`**, not its `id` (`COD2023…`, which is the register's own key and
  becomes `sourceChargePointId`). 449 of the 35.546 names are not EVSE-IDs (`ES*INC*E JAUME I - RRCC`, `ES*CAS*P3`; ids like `ES*PAV*E_VIN_005`
  and `ES*814*E-03` are kept). Only a name of the shape `ES*<3 characters>*E<…>` without spaces is kept as
  `evseId`; the rest keep the plug without one, so the live-availability join never sees a junk id.
- **Postcodes lose the leading zero** on 3.369 sites (`7011` for Palma, `08xxx` Barcelona): four digits are padded
  to five. **Street and city** are address lines prefixed with their label (`Dirección: …`, `Municipio: …`); they
  are read by label, not by position.

**Station shape — decision (owner, 2026-10-01): bundle by position, all operators.** The register has one site
*per operator*, and 4.363 site pairs are within 35 m of each other, up to 22 sites on one point. The ingestion
treats 30 m as "the same place" (`nearby` is only consulted for a source id it does not know) and then replaces
that station's charge points, so two sites of one source inside 30 m each overwrite the other on every run —
the IRVE failure. Clustering at 35 m (as DIEMO does, `CLUSTER_RADIUS_METRES`, both through `sync.support.PositionClusters`) merges what the ingestion would
merge anyway, without losing any charge point: **10.217 stations, none within 30 m of another**. Bundling only
sites of the same operator was measured and rejected: 441 stations with 1.490 charge points (4 %) would still
collide. Each station is keyed by the id of its first site (south to north, then west to east, then by id), which
is stable across editions, unlike DIEMO's coordinate key.

**Accepted limit, and a prerequisite of phase 5.** `master.charge_point` has no operator column; the operator exists
only on the station. A bundled station therefore carries the operator with most charge points: 250 of the 10.217
stations hold more than one operator, 647 charge points (1,8 %) belong to a minority operator whose name is not
shown. The right fix is an operator **per charge point** (migration, API field, iOS), which phase 5 needs anyway —
a price depends on the operator of the charge point, not of the parking lot — and is therefore recorded there
(owner decision, 2026-10-01). Not done in L4.

**Authority.** `ES: MITERD` joins `evmap.sync.authority`: the register is legally mandated, and without an entry any
later source for Spain would overwrite its EVSE-IDs. Source token `MITERD`, adapter `sync.es`.

**Implemented as `sync.es` (`MiterdSourceAdapter`, `MiterdDatexParser`, `MiterdProperties`, source token `MITERD`,
`evmap.sync.miterd.*`, `MITERD_ENABLED` / `MITERD_URL`).** Run against the live 2026-10-01 file: **10.217 stations from
35.546 charge points** (12.037 sites; 1.123 stations bundle several sites), 42.686 plugs, 35.097 EVSE-IDs kept, all of
them unique, and every station id and charge point id unique. The closest two stations are 35,05 m apart, so the
ingestion's 30 m match cannot merge two of them. Parsing takes 0,5 s and ~210 MB of heap; the download 7 s. Six
connectors rated 60 W read as unknown. The document is read with a streaming parser (StAX, DTDs and external
entities off) and held as small raw records until bundling is done; the stations are collected rather than streamed
lazily because their counters go into one summary line, and 10.000 of them are small.

Accepted edges: every station is labelled `ES`, including any near the French and Portuguese border that the
register lists as Spanish; the station name is the anchor site's name and is often a car park or operator label; the
postcode padding assumes the four-digit values are provinces 01–09, which is what the register's own numeric storage
produces. The register carries opening hours only as free text, and none are read.

## Open points

4. **OpenStreetMap / ODbL.** Options: (a) leave OSM out entirely and accept blank countries; (b) ingest it,
   flag those stations by source, and carry the ODbL attribution and share-alike obligations in the app;
   (c) defer until a country actually matters enough to justify the licence work.

5. **(Resolved 2026-09-30, see "Switzerland (L2)")** **Whether to model per-country source precedence explicitly.** Today "BNetzA wins ties for German
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

8. **First production run of `DIEMO`, and of the authority rule.** Expected: ~5.000 stations, most of them
   `created` and a share `updated` where they matched OCM's Swiss sites; the second consecutive run should
   report almost no `created`. Also worth a look afterwards: Swiss and French stations with EVSE-IDs
   (`SELECT source, count(*) FROM master.charge_point GROUP BY source`), which the authority rule is meant to
   keep stable across OCM runs. Not measured yet — needs the production database.

9. **First production run of `MITERD`.** Expected: ~10.000 stations, most of them `created` (Spain is not in the OCM
   crawl, so there is little to match), and a second consecutive run that reports almost no `created`. Also worth a
   look: the bundled stations should keep their ids across editions, since the anchor is the first site in
   south-to-north order and a new site south of an old anchor changes it — the ingestion then falls back to its
   30 m match and keeps the station, so this shows up as `updated`, never as a duplicate. Not measured — needs the
   production database.

10. **(Resolved 2026-10-01: option (a), see ADR 0022)** **Operator per charge point** (from "Spain (L4)"): moved to phase 5 of the roadmap. Options when it is taken up:
    (a) a nullable `operator_name` on `master.charge_point`, filled by every adapter that knows it, with the
    station's `operator_name` kept as the fallback; (b) a separate operator table keyed by name. (a) is smaller and
    matches how the directory (ADR 0014) already treats the name as the identity.

## References

- [Fichier consolidé des IRVE — transport.data.gouv.fr](https://transport.data.gouv.fr/datasets/fichier-consolide-des-bornes-de-recharge-pour-vehicules-electriques)
  — CSV `https://www.data.gouv.fr/api/1/datasets/r/eb76d20a-8501-400e-b336-d85724de5435`, GeoJSON
  `.../7eee8f09-5d1b-4f48-a304-5e99e8da1e26`
- [Ladestationen für Elektroautos — opendata.swiss](https://opendata.swiss/de/dataset/ladestationen-fuer-elektroautos)
  — data `https://data.geo.admin.ch/ch.bfe.ladestellen-elektromobilitaet/data/oicp/ch.bfe.ladestellen-elektromobilitaet.json`,
  status `.../status/oicp/...`
- [SFOE/ichtankestrom_Documentation](https://github.com/SFOE/ichtankestrom_Documentation) — Swiss feed docs
- [Piattaforma Unica Nazionale (PUN)](https://www.piattaformaunicanazionale.it/) and
  [GSE: PUN](https://www.gse.it/servizi-per-te/rinnovabili-per-i-trasporti/pun) — the Italian register
- [AgID/cruscotto-italia, `etl/sources/pun.py`](https://github.com/AgID/cruscotto-italia) — documents the
  June 2026 shutdown of the export and the remaining portal API
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
- [Puntos de recarga eléctrica para vehículos — DGT NAP](https://nap.dgt.es/en/dataset/puntos-de-recarga-electrica-para-vehiculos)
  — data `https://nap.dgt.es/datex2/v3/miterd/EnergyInfrastructureTablePublication/electrolineras.xml`, legal notice
  `https://www.dgt.es/contenido/aviso-legal/`
- `evmap_service/src/main/java/de/joinside/evmap_service/sync/es/` — the Spanish adapter
- `evmap_service/src/main/resources/application-sync.yaml` — `evmap.sync.irve.*`, `evmap.sync.miterd.*`
