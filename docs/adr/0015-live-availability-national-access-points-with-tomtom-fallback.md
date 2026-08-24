# 15. Live availability: national access points first, TomTom as fallback

- Status: Accepted — MobiData BW implemented, remaining providers outstanding
- Date: 2026-08-03, revised 2026-08-24
- Deciders: Johannes Popp

> **Revision 2026-08-24.** The first revision of this ADR was desk research; nothing had been checked
> against a live endpoint. Building the first provider disproved four of its load-bearing claims. The
> corrections are kept visible rather than silently edited away, because each one was a plausible
> assumption that a later reader would otherwise make again. See [Corrections](#corrections-2026-08-24).

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

### Source survey (2026-08-02 desk research; MobiData BW verified against the endpoint 2026-08-24)

| Source | Scope | Live status | Access | Licence / cost |
|---|---|---|---|---|
| **MobiData BW** (OCPDB) | BW-dense, DE-wide + CH | yes — 120.499 EVSEs with a non-static status | **OCPI 3.0** REST, no key, no registration | dl-de/by-2.0 |
| **Mobilithek** (DE NAP) | Germany | yes, ≤ 1 min | subscription per data offering, pull; DATEX II mandatory 14.04.2026 | free, registration with institutional address |
| **transport.data.gouv.fr** (FR NAP) | France | yes, per publisher | CSV per `schema-irve-dynamique`, joined via `id_pdc_itinerance`; **no national consolidation** | Licence Ouverte, no key |
| **NDW DOT-NL** (NL NAP) | Netherlands | yes | OCPI (NDW converts to DATEX II) | free, onboarding via NDW service desk |
| **NOBIL** | Norway | yes — WebSocket status stream, OCPI-fed | REST + WebSocket | free API key, Creative Commons |
| **TomTom EV Charging Stations Availability** | Europe-wide, > 800k live points | yes, by connector type and power | REST | free tier: 2.500 non-tile requests/day |
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

`sync` and `availability` do not reference each other. They meet at the charge point inventory
(`master.charge_point`), which `sync` writes and `availability` only reads.

### Identity resolution is an exact EVSE-ID join — not geography

This is the part the first revision got wrong, and it is the single most important design constraint,
so it is stated as a rule: **live status is attached to a charge point only on an exact identifier
match. There is no fuzzy, geographic, or name-based resolution anywhere in this feature.**

The identifier is the **EVSE-ID** (eMI3 / ISO 15118, e.g. `DE*EBW*E912316*1`), which AFIR obliges
operators to publish and which both sides already carry:

- BNetzA's register publishes it per charge point in the `EVSE-ID1..6` column group — 61.127 distinct
  IDs across 31 % of Ladeeinrichtungen in the 2026-07-28 edition. The parser previously read
  `Steckertypen{n}` and `Nennleistung Stecker{n}` from those same column groups and discarded
  `EVSE-ID{n}` while collapsing everything into per-station connector totals.
- MobiData BW's OCPDB keys its EVSEs on it (`evse_id`), for the CPO-fed dynamic sources.

Matching is on a **normalized** form — uppercased, with everything outside `[A-Z0-9]` removed — because
the same ID is published as `DE*EBW*E912316*1`, `DEAEWE002501` and `DE CSA 24D 006` in the same column.

Measured coverage, against 20.000 of the 120.499 live EVSEs on 2026-08-24: **14,5 % resolve to a BNetzA
Ladeeinrichtung.** The rest are charge points whose operator has not registered an EVSE-ID with BNetzA —
only 30,3 % of declared Ladepunkte carry one at all. That gap is answered with `UNKNOWN`, and it should
close on its own as AFIR registration is enforced.

A second path exists and was **deliberately not taken**: OCPDB synthesizes
`BNETZA*<Ladeeinrichtungs-ID>*<n>` for EVSEs it derived from the register itself, which reduces exactly
onto the `<Ladeeinrichtungs-ID>*<n>` key the BNetzA adapter writes as `source_charge_point_id`. It would
work, and it contributed 0,2 % of live EVSEs in the sample — 38 of 20.000. That is not worth a second
resolution path through a different column, so the provider skips these identifiers rather than emitting
them. One join, one column, one rule; the 0,2 % is spent on keeping it that way.

#### Why not geography

Geographic resolution was measured before it was rejected, because it looks like the obvious fallback
for the 85 % that do not match. Around Stuttgart, of 204 BNetzA stations, a live location lies within
25 m for 5 %, within 100 m for 20 %, within 200 m for 48 % — and the matches at those distances are
demonstrably wrong: a BNetzA station at *Hedelfinger Str. 21* takes a live station at *Nr. 25* as its
nearest neighbour, with a second candidate 2 m further away. Operator names cannot disambiguate,
because the register names the legal entity and the feed names the brand (`BP Europa SE` vs.
`Aral pulse`, `IEG Feuerbach GmbH & Co. KG` vs. `MENNEKES Digital Services GmbH`).

A wrong live status is worse than none: it is the one field in the app that users would drive to.
Low coverage is a disappointment, a confidently displayed wrong answer is a broken product.

The same rule disposes of the first revision's open point 1 — TomTom, having no EVSE-ID, does not get
a learned coordinate pairing either. It is either matched on an identifier or it is not used.

### Per-charge-point inventory (`master.charge_point`)

Live availability is per EVSE; the model had no EVSE. `master.charging_connector` is an aggregate
(`connector_type`, `power_kw`, `quantity`) with no charge point identity, so there was nothing for a
status to attach to and no place to keep the EVSE-ID that makes the join possible.

Migration `006-charge-point-inventory.sql` adds `master.charge_point` — one row per physically
countable charge point, carrying its `evse_id` — and gives `master.charging_connector` a **nullable**
`charge_point_id`. Nullable is the whole point of the design:

- Sources that describe charge points individually (BNetzA via its column groups, IRVE via one row per
  `id_pdc_itinerance`) attach their connectors to a charge point.
- Sources that only report totals (OCM, whose `Connection` carries a `Quantity` and no identifier)
  keep attaching connectors straight to the station, exactly as before.

Existing queries, filters and the `/api/v1/stations` payload are untouched, so this is additive rather
than a rewrite of the ingestion. `SourceStation` gains an optional list of charge points; an adapter
that supplies none behaves exactly as it did.

### The API serves it separately from the station

`GET /api/v1/stations/{id}/availability` — a distinct endpoint, not a field on the station payload. Two
reasons: station reads are cacheable for hours and this is not, and a live source that is down must
degrade to "unknown" without taking the map with it. It stays on the public, unauthenticated side
alongside `GET /api/v1/stations/**` in `SecurityConfiguration`, which its path already matches.

The map is served by `GET /api/v1/stations/availability?…` over a viewport, answering only for the
stations that have a live status at all. Live pins were affordable here in a way the first revision
assumed they would not be: MobiData BW needs no key, enforces no quota, and the whole live picture is
120k EVSEs — small enough to hold in memory, so a viewport query costs no upstream request at all.
This does not generalize to TomTom, which stays detail-only and budgeted.

Freshness is explicit in both responses — the client renders "vor 2 Min." or nothing, never a stale
value dressed as current. `UNKNOWN` is a first-class answer and must be shown as such, because the
honest failure mode here is "we don't know", and the dishonest one is "free".

### The cache is in-process, and that is a documented scale-out blocker

There was no caching infrastructure of any kind to build on — no Caffeine, no Redis, no
`spring-boot-starter-cache`. Rather than add a dependency for a map with a one-minute TTL,
`availability` keeps its own, keyed by provider and bounding box.

Providers are queried **on demand, by area** — not polled in full on a schedule, which was the first
revision's assumption. Polling looked cheaper until the field semantics were checked: OCPDB's
`last_updated` tracks the *static* description, not the status (records exist with a 2024
`last_updated` and a status from minutes ago), so an incremental refresh would miss precisely the
changes it exists to catch, and a correct full refresh costs ~240 requests per cycle whether or not
anyone is looking. On-demand makes the request count follow actual usage instead of dataset size, and
the TTL is set to one minute because that is the freshness AFIR already obliges operators to deliver.

This is correct for exactly one API container, which is what Compose runs today. With two, each would
poll independently — harmless for MobiData BW, which is unauthenticated and unmetered, but wrong for
TomTom, whose budget is per-key and not per-container. **Adding a second API replica requires moving
this cache out of process first** (a shared store, or the dedicated poller container this ADR's first
revision sketched). Recorded here because nothing in the code will fail loudly when that day comes.

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

1. **MobiData BW** — done. One OCPI 3.0 endpoint, no registration, open licence, real AFIR dynamic data
   from several CPOs, and the cheapest way to build the whole mechanism against real data.
2. **TomTom fallback**, so there is Europe-wide coverage — subject to the EVSE-ID rule above, which
   may mean TomTom is not usable at all and the fallback slot goes unfilled.
3. **France**, now that IRVE stores `id_pdc_itinerance` per charge point.
4. **Mobilithek** for Germany once DATEX II is the mandatory format.
5. NL and NO as their own providers afterwards.

## Corrections (2026-08-24)

Four claims from the first revision, and what building against the endpoint showed.

1. **"The French dynamic schema joins on `id_pdc_itinerance`, which the IRVE adapter already stores as
   `source_station_id`."** It did not. `IrveCsvParser` grouped rows by `id_station_itinerance` (falling
   back to `id_station_local`) and stored *that*; `id_pdc_itinerance` was never read. The consequence
   drawn from it — "no schema migration for the NAP path" — was therefore false for France too, not
   only for TomTom. The charge point inventory fixes this: IRVE's one-row-per-`pdc` shape maps onto
   `charge_point` directly.

2. **"`master.station_source` is the whole mechanism needed for any country whose static register is
   already ingested."** True only when the same publisher supplies both axes. MobiData BW is a
   *different* publisher from BNetzA: its live records (`datex2_ecomovement`, `datex2_chargecloud`,
   `datex2_tesla`) are separate locations with their own ids, and OCPDB does not merge them with the
   `bnetza_api` locations it also serves. `station_source` carries BNetzA's `Ladeeinrichtungs-ID`,
   which no live record references. The join had to be found one level down, at the EVSE-ID.

3. **"Status values live in a short-TTL in-process cache."** Stated as though the cache existed. It did
   not, in any form, and neither did a dependency offering one.

4. **The per-EVSE nature of the data was never reconciled with the model.** `LiveAvailability` was
   specified as a single value while the thing it describes is one charge point among several, and the
   schema had no charge point at all. This is what forced `master.charge_point`.

## Consequences

### Positive

- The one question a charging app exists to answer becomes answerable, from sources that are legally
  obliged to be free and to be current within a minute.
- Resolution is exact. A displayed live status is either right or absent; there is no class of
  plausible-looking wrong answers, and no tuning threshold that silently changes correctness.
- Live status cannot corrupt master data: separate package, separate endpoint, no writes to `master.*`,
  no path through `StationIngestionPort`.
- Per-country providers fail independently — the containment principle of ADR 0013, applied to a
  second axis.
- The charge point inventory is worth having on its own. "4 Ladepunkte, davon 2 mit CCS" is a better
  answer than a connector total, and it is the natural home for per-EVSE data the app may later want.
- No key, no quota and no registration for the first provider, so the feature ships without a vendor
  relationship.

### Negative / accepted risks

- **This reverses a stated v1 non-goal (Lastenheft §10).** Deliberate; the Lastenheft is amended in the
  same change rather than quietly diverged from.
- **Coverage starts low — 14,5 % of live EVSEs resolve.** This is the price of refusing fuzzy matching,
  and it is paid in visible blank space on the station detail screen. It improves only as operators
  register EVSE-IDs with BNetzA, which is outside this project's control.
- **N integrations per country, indefinitely.**
- **The fallback has a vendor and a ceiling**, and under the EVSE-ID rule may turn out to be unusable.
- **Live data invites trust it cannot always earn.** A CPO that reports late, or a NAP feed that lags,
  shows a free stall that is occupied. The one-minute AFIR rule is an obligation, not a guarantee.
- **The in-process cache blocks horizontal scaling of the API container**, as described above.
- **Operational cost rises.** A continuously refreshed picture is a different runtime profile from a
  daily batch, and it is the first thing in the API container that is neither stateless nor
  request-driven.
- **The charge point inventory doubles some ingestion work** — BNetzA now writes up to six charge point
  rows plus their connectors per Ladeeinrichtung instead of one collapsed connector set.

## Open points

1. **What the client shows when sources disagree** — a live feed saying `OCCUPIED` and the register's
   own `availability_status` saying `MAINTENANCE` are not contradictory but need a display rule.
   Currently the live value wins where present and the reported one is shown otherwise.

2. **Whether TomTom can be used at all** under the exact-identifier rule. If its response carries no
   EVSE-ID, the fallback slot stays empty and coverage outside MobiData BW's reach is zero until the
   next NAP adapter lands. Worth confirming against their endpoint before assuming the slot is filled.

3. **How duplicate EVSE-IDs are handled.** 1.657 of the register's EVSE-ID occurrences are repeats of
   an ID used elsewhere in the same edition. Currently the first wins and the rest are counted and
   logged; whether that is right depends on whether they are publisher errors or genuinely shared
   infrastructure.

4. **When the availability poller moves out of the API container** — tied to the first day a second
   replica is wanted, and to whether Mobilithek's per-CPO subscriptions make the polling profile
   heavier than one fixed-delay refresh.

## References

- `sync/AvailabilityStatus.java` — the *reported* status field this ADR is explicitly not about
- `master.charge_point` (`006-charge-point-inventory.sql`) — the charge point inventory the join needs
- `master.station_source` (`001-initial-schema.sql`) — the station-level identity the sync axis uses
- [ADR 0012](0012-additional-national-charging-registers.md) — AFIR Art. 20 as the reason national sources keep appearing
- [ADR 0013](0013-per-source-isolation-in-the-sync-run.md) — per-source containment, the pattern `availability` copies
- [ADR 0007](0007-batched-ingestion-transactions.md) — why the batch ingestion model does not fit live data
- [ADR 0006](0006-open-charge-map-source-adapter.md) — OCM, which supplies no live status and no charge point identity
- Lastenheft §10 — the non-goal this ADR reverses
- [MobiData BW OCPDB API](https://api.mobidata-bw.de/ocpdb/documentation/public.html) — OCPI 3.0, no credentials
