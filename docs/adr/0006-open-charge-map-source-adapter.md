# 6. Open Charge Map source adapter: keyed, throttled, country-scoped crawl

- Status: Accepted
- Date: 2026-07-28
- Deciders: Johannes Popp

## Context

Open Charge Map is the Lastenheft's (§5) international source: BNetzA covers Germany authoritatively,
OCM covers the rest of Europe and contributes community-maintained detail. Unlike the
Bundesnetzagentur register it is a live REST API with an operator who can — and says they will — cut
off callers who use it badly.

Three constraints came out of the research and each one shaped the design.

**An API key is mandatory.** Verified: `GET https://api.openchargemap.io/v3/referencedata/` without a
key returns `403` and

```
You must specify an API key using the key query parameter or x-api-key header.
```

Keys are free but require a human step: sign in at `openchargemap.org` → *my profile* → *my apps* →
*Register An Application*. Nothing in the build can obtain one automatically.

**The fair usage policy has teeth.** Quoting the OCM API specification:

> *Do not repeatedly call the API with duplicate queries. Debounce/throttle your API requests to
> minimise the work our API has to do. The API administrator (Open Charge Map) reserves the right to
> ban API callers (including automated banning) if callers make excessive/indiscriminate use of the
> API, at the discretion of the OCM administrator.*
>
> If you need to make a high volume of queries against the API please host your own API mirror or
> import the data into your own API.

A naive "crawl all of Europe every 24 hours" loop is precisely the described failure.

**Licensing is mixed.** OCM aggregates feeds whose licences and required attribution differ per data
provider. `opendata=true` restricts results to explicitly open-licensed data. The Lastenheft requires
per-station provenance and naming of the source in the app.

## Decision

**Crawl a configured list of countries with keyset paging, one throttled request at a time, under a
hard page cap, restricted to open data.**

### Scope: DACH plus neighbours, configurable

`evmap.sync.ocm.country-codes` defaults to `DE, AT, CH, NL, BE, LU, FR, IT, DK, PL, CZ` — the
Lastenheft's "primarily DACH, secondarily EU" target. This is a deliberate bound on request volume,
not a technical limit. Widening it is a configuration change, but one to make consciously: full
European coverage is the point at which OCM's own advice ("host your own API mirror or import the
data into your own API") starts to apply, and cloning their published dataset would be the better
route rather than a larger live crawl.

### Paging: keyset, not offset

OCM offers no page/offset parameter. Each request asks for ids above the last one seen:

```
GET /poi/?countrycode=DE&sortby=id_asc&greaterthanid=<last>&maxresults=500
        &compact=true&verbose=false&opendata=true&client=evmap
```

`sortby=id_asc` makes that a total order, so the crawl stays consistent while the underlying data
changes. A page shorter than `maxresults` ends the country — the crawl does not spend a request
proving the next page is empty.

`max-pages-per-country` (default 200, so 100.000 sites) is a safety stop. A paging bug that fails to
advance `greaterthanid` would otherwise become an unbounded hammering of an API whose operator bans
for exactly that. It logs a warning naming the property when it fires.

`request-delay` (default 1s) throttles between requests, as the policy asks.

### `compact=true&verbose=false` plus one reference-data lookup

Compact responses replace repeated reference objects with numeric ids. `/referencedata` is fetched
**once per run** and used to resolve `ConnectionTypeID` → title and `OperatorID` → name. The
alternative — verbose responses — repeats the same operator and connector objects on every one of
~100k results, for no additional information.

Reference data failing is **not** fatal: the run continues with unresolved ids, which costs connector
types and operator names on that run but still maps every site. Failing instead would cost the entire
international dataset over a lookup table.

The country code is taken from the *request*, not the payload: compact results carry only a numeric
`CountryID`, and each crawl already asks for exactly one country. That removes a second lookup table
and a whole class of mismatch.

### Licence: `opendata=true`

Restricting to open-licensed feeds keeps attribution satisfiable by the existing per-station source
display. Ingesting everything would require storing the per-provider licence and attribution string
and surfacing them in the iOS station detail — a schema and client change, for coverage that is not
needed in v1.

### Incremental fetching, with two safety nets

After a country has been crawled in full once, later runs pass `modifiedsince` and fetch only what
changed. The high-water mark per (source, scope) lives in `master.source_sync_state`, reached through
`SyncStateStore` — deliberately a separate, narrow interface from `StationIngestionPort`, so an
adapter can track its own progress without being handed the port that writes master data.

Three rules make this safe rather than merely faster:

**The watermark is the run's start time, not the newest timestamp observed.** `modifiedsince` is
compared against whatever OCM considers a modification date, which is not necessarily any field the
response exposes. Anchoring on our own clock avoids depending on that.

**It advances only after the ingestion confirms the run committed.** Records leave `fetchStations()`
long before they are stored, so a watermark moved during the crawl would permanently skip anything
fetched and then lost to a failure — the one way an incremental sync loses data silently instead of
merely being late. `SourceAdapter.commitProgress()` exists for this: `SyncJob` calls it only when the
run finished with zero failed records (ADR 0007). A country whose crawl was cut short by the page cap
earns no watermark either.

**`watermark-overlap` (2 days) re-asks slightly before the mark, and `full-refresh-interval` (7 days)
forces a whole-country crawl regardless.** The overlap absorbs clock skew against OCM and re-delivers
anything a failed run had fetched; ingestion is an upsert, so it costs bandwidth and nothing else.
The full refresh exists because `modifiedsince` never reveals deletions, and only reveals edits OCM
itself counts as a modification — without a periodic sweep the two copies drift apart indefinitely.

### Availability by explicit status id, because `IsOperational` is not a usability signal

An earlier revision of this decision mapped `StatusTypeID` purely through the reference data's
`IsOperational` flag. Checking the live reference data on 2026-07-28 showed that is wrong:

| ID | Title | `IsOperational` |
|---|---|---|
| 30 | Temporarily Unavailable | **`true`** |
| 150 | Planned For Future Date | `false` |
| 200 | Removed (Decommissioned) | `false` |
| 210 | Removed (Duplicate Listing) | `false` |

The flag means "is this status one of the operational category", not "can a driver charge here".
Trusting it would have advertised every temporarily unavailable station as usable — the exact
failure the availability field exists to prevent — while treating a decommissioned station as merely
out of service.

So the ten known status ids are mapped explicitly:

- 10, 20, 50, 75 → `OPERATIONAL`. "Currently In Use" counts: the station works, it is merely
  occupied, and live occupancy is a v1 non-goal either way.
- 30 → `MAINTENANCE`, the analogue of BNetzA's `In Wartung`.
- 100 → `OUT_OF_SERVICE`.
- **150, 200, 210 → the site is not ingested at all.** A planned, decommissioned or duplicate listing
  is not installed infrastructure; mapping it to any availability value would put a charger on the
  map that nobody can drive to, and a duplicate listing additionally works against the
  geo-deduplication in the ingestion port.
- 0 ("Unknown") → `null`. We do not know, which is not the same as "it works".

Ids OCM adds later fall back to `IsOperational` — coarser, but better than discarding the signal.

### A missing key skips the adapter, it does not fail the run

A blank `api-key` logs a warning naming the property and the registration URL, and returns an empty
stream. A deployment running BNetzA only is legitimate; failing the sync run over a missing optional
key would take German coverage down along with the international data.

### One connector label needed correcting too

`Europlug 2-Pin (CEE 7/16)` appears in German OCM data and was being normalised to `CEE` by the
shared vocabulary, because its formal designation contains "CEE". It is an ordinary 230 V household
plug, not the industrial blue/red CEE connector someone filtering for CEE is looking for. It now maps
to `Schuko`, alongside `CEE 7/4 - Schuko - Type F`.

### Explicit JSON property names

OCM serialises PascalCase (`ID`, `AddressInfo`, `ConnectionTypeID`). The wire records declare
`@JsonProperty` explicitly rather than relying on OCM's `camelcase=true` switch, so the mapping does
not depend on a transformation applied on their side. Only mapped fields are modelled and unknown
ones are ignored, so a new upstream field cannot break a run.

## Consequences

### Positive

- **Verified against the live API on 2026-07-28**, which is what caught the two mapping errors above.
  Confirmed: the key authenticates via `X-API-Key`; `/referencedata` returns `ConnectionTypes[].Title`,
  `Operators[].Title` and `StatusTypes[].{ID,Title,IsOperational}` exactly as modelled; compact POI
  results carry `ID`, `OperatorID`, `StatusTypeID`, `AddressInfo{Title,AddressLine1,Town,Postcode,
  Latitude,Longitude}` and `Connections[]{ConnectionTypeID,PowerKW,Quantity}`; `maxresults=500` is
  honoured, so the "short page ends the country" rule is sound; and a 2.000-site German sample mapped
  14 of its 15 distinct connector titles onto the canonical vocabulary, the 15th being an Australian
  plug kept as its raw label.
- Request volume is bounded and visible: countries × pages, throttled, capped. Roughly a few hundred
  requests per daily run at the default scope.
- Well-behaved by OCM's own criteria: keyed, throttled, identified by both `client` and a custom
  `User-Agent`, no duplicate queries.
- Degrades in the right direction at every failure point — no key, no reference data, or a site
  without coordinates each cost exactly what they should and nothing more.
- Covered by fifteen tests against a mock server: reference-data resolution, keyset paging and its
  stop condition, per-country crawling, key and caller identity actually being sent, the missing-key
  skip, the reference-data outage path, dropping of unmappable sites, the page cap, availability
  mapping, and each incremental rule — first-run full crawl, `modifiedsince` on later runs, stale
  watermark forcing a full crawl, watermarks advancing only on `commitProgress()`, a truncated crawl
  earning nothing, and per-country independence.

### Negative / accepted risks

- **Coverage is deliberately incomplete** — 11 countries, open-data feeds only. Both are single
  configuration values, but widening either has consequences (fair use, attribution) rather than
  being free.
- **`modifiedsince` filters, but against a field OCM does not expose.** Verified that it works and
  narrows results (a German page went from 200 to 14 hits for a three-week window), and that
  ISO-8601 UTC is accepted. What could not be verified is *which* upstream field it compares — so an
  edit OCM does not count as a modification is still missed until the weekly full refresh. The
  overlap and refresh interval remain the mitigation.
- **A run that loses a single record advances no watermark at all**, for any country. Correct but
  blunt: one bad station anywhere makes the next run re-fetch every country's overlap window.
- **Deletions upstream are not propagated.** A site removed from OCM stays in `master` — true of the
  BNetzA adapter as well, and a property of the ingestion port rather than of either adapter. The
  weekly full crawl re-fetches such a site's absence but nothing acts on it.

## Open points

1. **Whether to ingest non-open-data feeds.** Requires storing per-provider licence and attribution
   (`master.station_source.field_provenance` is a natural home) and displaying them in the iOS
   station detail. Worth revisiting if coverage in the target countries proves thin.

2. **Widening the country list.** Full European coverage is the point at which OCM's own advice
   ("host your own API mirror or import the data into your own API") applies. Cloning their published
   GitHub dataset would then be the better route than a larger live crawl — incremental fetching
   reduces the volume but does not change that argument for the initial full crawl of each country.

3. **Watermark granularity.** A single failed record blocks every country's watermark. Per-country
   failure attribution would fix it, but the ingestion reports failures per run, not per source or
   scope, and threading that through is more machinery than the current failure rate justifies.

## References

- [Open Charge Map API specification](https://raw.githubusercontent.com/openchargemap/ocm-docs/refs/heads/master/Model/schema/ocm-openapi-spec.yaml) — endpoints, parameters, fair usage policy
- [Open Charge Map terms](https://openchargemap.org/site/about/terms)
- `evmap_service/src/main/java/de/joinside/evmap_service/sync/ocm/` — the adapter
- `evmap_service/src/main/resources/application-sync.yaml` — `evmap.sync.ocm.*`
- [ADR 0005](0005-bundesnetzagentur-source-adapter.md) — the authoritative German source and the
  shared connector vocabulary
- [ADR 0007](0007-batched-ingestion-transactions.md) — the batched ingestion whose completion signal
  gates the watermark
