# 15. Live availability: national access points first, TomTom as fallback

- Status: Accepted — no implementation yet
- Date: 2026-08-03
- Deciders: Johannes Popp

## Context

`master.charging_station.availability_status` already exists and is deliberately *not* what this ADR is
about. It carries **reported** service state from the register that supplied the station — BNetzA's
`In Betrieb` vs. `In Wartung`, OCM's `IsOperational` flag — refreshed once per sync run, i.e. once per
24 h. `AvailabilityStatus` says so in its own javadoc, and points at Lastenheft §10, where real-time
availability is an explicit v1 non-goal.

That non-goal is what this ADR changes. "Is this charge point free right now, or occupied, or broken"
is the question users open a charging app to answer, and answering it with a 24-hour-old register
field is worse than not answering it: it looks live and is not.

Two things make it feasible now that were not when the sync package was written.

**AFIR Article 20 has been in force since 14 April 2025.** Every operator of publicly accessible
charge points in the EU must publish *dynamic* data — occupancy, operational status, ad-hoc price —
free of charge and without discrimination, through the member state's National Access Point, updated
**within one minute** of the event. The same regulation that turned static registers into an adapter
shape (ADR 0012) is now doing it for live status. From **14 April 2026** the German feed format is
mandatory DATEX II.

**The gap is aggregation, not availability.** The data is legally free; nobody is obliged to serve it
as one endpoint. Germany's Mobilithek requires a subscription *per data offering*, and offerings are
per CPO; France publishes a dynamic IRVE schema but explicitly has **no national consolidation** of the
dynamic feeds. So "free" costs N integrations per country, and coverage before those N are done is
zero — which is exactly the shape that needs a fallback.

### Source survey (desk research, 2026-08-02 — nothing verified against a live endpoint yet)

| Source | Scope | Live status | Access | Licence / cost |
|---|---|---|---|---|
| **MobiData BW** | Baden-Württemberg (+ CH) | yes — AFIR dynamic data from EnBW mobility+, Tesla, chargecloud, Eco-Movement, Stadtwerke | OCPI API, DATEX II, CSV | dl-de/by-2.0, no key |
| **Mobilithek** (DE NAP) | Germany | yes, ≤ 1 min | subscription per data offering, pull; DATEX II mandatory 14.04.2026 | free, registration with institutional address |
| **transport.data.gouv.fr** (FR NAP) | France | yes, per publisher | CSV per `schema-irve-dynamique`, joined via `id_pdc_itinerance`; **no national consolidation** | Licence Ouverte, no key |
| **NDW DOT-NL** (NL NAP) | Netherlands | yes | OCPI (NDW converts to DATEX II) | free, onboarding via NDW service desk |
| **NOBIL** | Norway | yes — WebSocket status stream, OCPI-fed (Mer, Recharge, Vattenfall, E.ON …) | REST + WebSocket | free API key, Creative Commons |
| **TomTom EV Charging Stations Availability** | Europe-wide, > 800k live points | yes, by connector type and power | REST | free tier, no credit card: 2.500 non-tile requests/day, commercial use permitted |
| Open Charge Map | — | **no** | — | `StatusType` is "generally operational" plus community check-ins, not occupancy |
| Hubject / Gireve | Europe | yes | requires a CPO/eMSP roaming contract | not reachable for a map client |

Also considered and rejected as a *source*: reading CPO backends directly, the way the Android
`ev-map/EVMap` does. It works, it is undocumented, it breaks without notice, and it is
terms-of-service-grey. AFIR made the legitimate route exist; there is no reason to take the other one.

## Decision

**Live availability is a second data axis, sourced per country from national access points, with
TomTom's free tier as the fallback wherever no national adapter has been written yet.**

### It does not go through `sync`

`sync` exists to write master data: batch, 24 h fixed delay, `StationIngestionPort` as the sole write
boundary, `BatchedIngestion` committing thousands of rows per run (ADR 0007). Live status is the
opposite of every one of those properties — it is volatile, per-EVSE, worthless after minutes, and must
never be written into `master.*`, because a value with a one-minute half-life does not belong in a table
whose other columns are refreshed daily and carry per-field provenance.

A new package, `de.joinside.evmap_service.availability`, owns it, and the layering mirrors `sync`:

- `AvailabilityProvider` — the interface every live source implements, the counterpart to
  `SourceAdapter`. Like `SourceAdapter` it declares `source()` and `enabled()`, and like the sync
  package, nothing in `availability` names a provider; the registry is whatever the component scan
  found. `AvailabilityProviderRegistrationTests` asserts the full set, for the same reason
  `SourceAdapterRegistrationTests` does.
- `LiveAvailability` — the normalized record a provider emits, over a **closed vocabulary** the iOS
  client mirrors, exactly as `ConnectorTypes` and `AvailabilityStatus` already work: `AVAILABLE`,
  `OCCUPIED`, `OUT_OF_ORDER`, `UNKNOWN`. Adding a value on one side without the other either hides it
  or shows a raw token to users.
- No Liquibase table for status values. They live in a short-TTL in-process cache, and a run that is
  lost is re-fetched rather than recovered.

`sync` and `availability` do not reference each other. They meet at `master.station_source`.

### Identity resolution runs through `master.station_source`

The hard part is not fetching status, it is knowing which station it belongs to. `master.station_source`
already maps `(source, source_station_id) → station_id`, which is the whole mechanism needed for any
country whose *static* register is already ingested: the French dynamic schema joins to the static one
on `id_pdc_itinerance`, which is the identifier the IRVE adapter already stores as
`source_station_id`. Same source token, same key, existing table — no new mapping layer, and no
schema change for the NAP providers.

The exception is TomTom, which has no static ingestion and therefore no key in `station_source`.
Resolving it is an open point (below) and is a real cost of the fallback, not an afterthought.

### The API serves it separately from the station

`GET /api/v1/stations/{id}/availability` — a distinct endpoint, not a field on the station payload. Two
reasons: station reads are cacheable for hours and this is not, and a live source that is down must
degrade to "unknown" without taking the map with it. It stays on the public, unauthenticated side
alongside `GET /api/v1/stations/**` in `SecurityConfiguration`.

Freshness is explicit in the response — the client renders "vor 2 Min." or nothing, never a stale value
dressed as current. `UNKNOWN` is a first-class answer and must be shown as such, because the honest
failure mode here is "we don't know", and the dishonest one is "free".

### TomTom is a fallback, and the quota is a hard budget

2.500 non-tile requests per day is roughly 1,7 per minute sustained. That is a *fallback for gaps in
coverage*, not a backend for a map full of pins, and the design has to make that impossible rather than
merely discouraged:

- it is consulted **only** for stations in countries with no registered NAP provider,
- it is consulted **only** for a station the user actually opened, never for a viewport,
- results are cached with a TTL, and the daily budget is enforced in the provider itself — exhausted
  budget returns `UNKNOWN`, it does not queue, retry, or degrade the station endpoint.

Being over the free quota must be a logged, healthy state, not an incident.

### Order of implementation

1. **MobiData BW** first. One OCPI endpoint, no registration, open licence, real AFIR dynamic data from
   several CPOs — the cheapest way to build the whole mechanism (provider, vocabulary, cache, endpoint,
   iOS rendering) against real data before touching subscription-gated feeds.
2. **TomTom fallback**, so there is Europe-wide coverage the moment the feature ships.
3. **France**, because IRVE static is already ingested and the join key is already stored.
4. **Mobilithek** for Germany once DATEX II is the mandatory format, so the parser is written once
   against the format that will still be there.
5. NL and NO as their own providers afterwards.

## Consequences

### Positive

- The one question a charging app exists to answer becomes answerable, from sources that are legally
  obliged to be free and to be current within a minute.
- Live status cannot corrupt master data: separate package, separate endpoint, no writes to `master.*`,
  no path through `StationIngestionPort`.
- Per-country providers fail independently — the containment principle of ADR 0013, applied to a
  second axis. One national portal being down costs its own country an availability display.
- Coverage is never zero: an unimplemented country falls back rather than showing nothing.
- No schema migration for the NAP path. `master.station_source` already carries the join.

### Negative / accepted risks

- **This reverses a stated v1 non-goal (Lastenheft §10).** Deliberate, and the Lastenheft should be
  amended rather than quietly diverged from.
- **N integrations per country, indefinitely.** Germany's per-CPO subscription model means DE coverage
  is partial until a Germany-wide offering exists or enough subscriptions are held. Accepted; the
  fallback is what makes partial coverage tolerable instead of embarrassing.
- **The fallback has a vendor and a ceiling.** TomTom's free tier is a business decision by TomTom, and
  2.500/day does not survive real traffic. It is a bridge, and if the app gets traffic it becomes either
  a paid tier or dead weight to be removed once NAP coverage is broad.
- **Live data invites trust it cannot always earn.** A CPO that reports late, or a NAP feed that lags,
  shows a free stall that is occupied. The one-minute AFIR rule is an obligation, not a guarantee.
  Timestamping every value and showing `UNKNOWN` honestly is the mitigation, and it is not a complete one.
- **Operational cost rises.** Polling several feeds continuously is a different runtime profile from a
  daily batch, and it is the first thing in the backend that is neither stateless-API nor scheduled-job.

## Open points

1. **How TomTom-sourced status resolves to a station.** No `station_source` key exists for it. Options:
   (a) resolve on demand by coordinate + connector match at station-open time and cache the pairing;
   (b) store a `station_source` row with source token `TOMTOM` once resolved, reusing the existing
   table as a learned mapping; (c) skip resolution and query TomTom by the station's own coordinates
   every time, accepting mismatch risk. (b) is the cheapest to reason about but writes into a table
   `sync` owns, which needs a decision on who may write source rows.

2. **Where the cache lives.** In-process is simplest and correct for one API container; it becomes
   wrong the moment there are two, because each would poll independently and multiply the TomTom
   budget. Options: (a) in-process now, documented as a scale-out blocker; (b) a shared cache
   (Redis/Valkey) from the start; (c) a dedicated availability poller container, mirroring the
   API/sync split, writing to a shared store.

3. **Poll vs. pull-on-demand.** NOBIL pushes over WebSocket, OCPI feeds are polled, Mobilithek is a
   pull subscription. Whether to keep a continuously-refreshed picture for all known stations or fetch
   per station on demand determines both the runtime profile and whether the map can ever show live
   pins rather than only the detail screen.

4. **Whether live status feeds the map at all, or only the station detail screen.** Detail-only is far
   cheaper and is assumed above. Live pins on the map are the feature users would actually notice, and
   would rule out the TomTom fallback entirely on quota grounds.

5. **What the client shows when sources disagree** — a NAP feed saying `OCCUPIED` and the register's
   own `availability_status` saying `MAINTENANCE` are not contradictory but need a display rule.

6. **Whether MobiData BW's OCPI version is 2.2.1 or 3.0**, and whether the DATEX II endpoints there are
   live yet. Read from their dataset page, not verified against the endpoint.

## References

- `sync/AvailabilityStatus.java` — the *reported* status field this ADR is explicitly not about
- `master.station_source` (`001-initial-schema.sql`) — the identity join both axes share
- [ADR 0012](0012-additional-national-charging-registers.md) — AFIR Art. 20 as the reason national sources keep appearing
- [ADR 0013](0013-per-source-isolation-in-the-sync-run.md) — per-source containment, the pattern `availability` copies
- [ADR 0007](0007-batched-ingestion-transactions.md) — why the batch ingestion model does not fit live data
- [ADR 0006](0006-open-charge-map-source-adapter.md) — OCM, which supplies no live status
- Lastenheft §10 — the non-goal this ADR reverses
