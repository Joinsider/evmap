# 15. Live availability: national access points first, TomTom as fallback

- Status: Accepted — MobiData BW, France and the Mobilithek (L5) implemented; TomTom rejected
- Date: 2026-08-03, revised 2026-08-24, 2026-09-28, 2026-10-02
- Deciders: Johannes Popp

> **Revision 2026-08-24.** The first revision of this ADR was desk research; nothing had been checked
> against a live endpoint. Building the first provider disproved four of its load-bearing claims. The
> corrections are kept visible rather than silently edited away, because each one was a plausible
> assumption that a later reader would otherwise make again. See [Corrections](#corrections-2026-08-24).
>
> **Revision 2026-09-28.** France is implemented, against a national consolidation this ADR said did not
> exist; TomTom was checked and cannot be used under the EVSE-ID rule; the Mobilithek was surveyed and is
> blocked on organisation registration. See [France](#france-2026-09-28),
> [TomTom](#tomtom-2026-09-28) and [Mobilithek survey](#mobilithek-survey-2026-09-28).
>
> **Revision 2026-10-02.** The organisation is approved; the Mobilithek is built as gap filler L5. See
> [Mobilithek (L5)](#mobilithek-l5-2026-10-02).

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
| **transport.data.gouv.fr** (FR NAP) | France | yes — 111k charge points, ~66k reported within 72 h | one consolidated CSV of every publisher's `schema-irve-dynamique` (BETA), joined via `id_pdc_itinerance` — *corrected 2026-09-28* | Licence Ouverte, no key |
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
2. ~~**TomTom fallback**~~ — rejected 2026-09-28: no EVSE-ID in the response, see [TomTom](#tomtom-2026-09-28).
3. **France** — done 2026-09-28, see [France](#france-2026-09-28).
4. **Mobilithek** for Germany — done 2026-10-02 as gap filler L5, see [Mobilithek (L5)](#mobilithek-l5-2026-10-02).
5. NL and NO as their own providers afterwards. Neither is useful yet: both need an onboarding or key,
   and more to the point neither country has a static source in `sync` that writes EVSE-IDs — OCM
   supplies no charge point identity — so there is nothing for their live status to join onto.

## France (2026-09-28)

The first revision's premise was that France has "no national consolidation" of the dynamic feeds, so
it would cost one integration per publisher. That was true when written and is not now:
transport.data.gouv.fr publishes *[BETA] Base nationale consolidée IRVE – données dynamiques*, one CSV
of every publisher's `schema-irve-dynamique` (v2.3.0) regenerated per request by their proxy. Measured
on 2026-09-28: 121.965 rows, 111.122 distinct `id_pdc_itinerance`, ~1.000 of them changing per 90 s,
9 MB raw and 1,6 MB gzipped.

**90,1 % of the live ids resolve** against the `id_pdc_itinerance` the IRVE sync adapter already stores
as the charge point's EVSE-ID — against 14,5 % for Germany — because both files come from the same
publishers under the same schema family. No schema change was needed.

`availability.irve.IrveDynamicAvailabilityProvider` is shaped by what the source lacks:

- **No area query, no coordinates.** The file is the country and carries only ids, so a bounding box
  cannot be applied even after download. The provider answers every area with everything it has, and
  `AvailabilityService` now keeps only the identifiers it asked for — which it should always have done;
  it is also what keeps the cost of a map pan from scaling with the country.
- **Still on demand.** Nothing is downloaded until someone opens a French station or map area, and at
  most once per `refresh-interval` (1 min) after that. Only the request that finds the copy expired waits
  for the ~1 s download; concurrent ones get the previous copy. A failed refresh keeps the last good copy
  for `stale-after` (10 min), then France reads as unknown.
- **Staleness is filtered at the source.** A quarter of the rows are months old (p75 = 70 days) — feeds
  that stopped, not charge points that stayed free. Rows older than `max-age` (72 h) are dropped; 66k of
  111k survive. 72 h is a judgement: it keeps a rural charger nobody used over a weekend, and drops the
  dead feeds.
- **Two status axes collapse onto one.** `etat_pdc = hors_service` → `OUT_OF_ORDER` whatever the
  occupancy; `occupe`/`reserve` → `OCCUPIED`; `libre` → `AVAILABLE` *only* with `en_service`, because
  "free, state unknown" is the answer that sends a driver to a dead post; everything else `UNKNOWN`.
- Duplicate ids (operator plus roaming platform) resolve to the newest `horodatage`. Offset-less
  timestamps are read as Europe/Paris.

Privacy is better than MobiData BW's: the request carries no coordinates at all, so nothing about what
a user is looking at leaves the server.

## TomTom (2026-09-28)

Open point 2 is answered: the EV Charging Stations Availability API returns counts per connector type
and power level for a `chargingAvailability` id taken from a Search API result — no EVSE-ID and no
per-charge-point record. Using it would need a coordinate or name match from our station to TomTom's,
which is exactly the resolution this ADR forbids. **TomTom is not used**, and there is deliberately no
Europe-wide fallback; countries without a national provider read as unknown.

## Mobilithek survey (2026-09-28)

Checked against the live catalogue while signed in, ahead of step 4. Nothing is implemented yet; this
records what an adapter would have to handle, so the order above can be revisited with facts.

- **29 dynamic AFIR offerings** (`AFIR-recharging-dyn-*`) are listed, each paired with a static one:
  EnBW, Tesla, Volkswagen Group Charging, EWE, Eco-Movement, chargecloud, eliso, LichtBlick, GP JOULE,
  Qwello, Monta, SMATRICS, Wirelane, Road, e-clearing.net/ladenetz.de (smartlab), vaylens, Audi charging
  hub and a dozen smaller CPOs. Not found at the time: IONITY, Aral pulse, Allego.
- **Format is uniform:** DATEX II v3 as JSON, schema profile `AFIR-Recharging-Dynamic-01-00-00_Delta`
  (schemas at `/schemas/DATEX_2_V3/AFIR-Recharging-Dynamic-01-00-00_Delta/…`). One parser serves all
  29, which turns "N integrations" into N subscriptions plus one adapter.
- **Delivery is brokered and delta-based** (`mdpBrokering: true`, `deltaDelivery: true`,
  `accrualPeriodicity: ON_OCCURRENCE`). A consumer pulls from the Mobilithek broker, not from the CPO.
- **Licence is open** (e.g. EnBW: CC BY 4.0), and `providerApprovalRequired: false`: subscribing needs
  no approval from the CPO.
- **Access requires a registered organisation.** A personal account can browse but not subscribe. The
  account has to register an organisation (reviewed and activated by the Mobilithek; "company, authority,
  office, university department or other unit"), hold the *Bestell-Manager* role, and create a machine
  account whose PKCS#12 certificate authenticates the pull via mTLS (`https://mobilithek.info:8443/…`,
  conditional GET with `If-Modified-Since`/ETag, 304 = no change). Whether a sole proprietorship
  (Einzelunternehmen) or a non-German entity qualifies is **not documented** — to be asked of the
  Mobilithek support. The exact path per endpoint kind is in the *Technische Schnittstellenbeschreibung*
  v1.3.2 (07.11.2025) and must be verified before coding.
- The public catalogue search (`/mdp-api/mdp-msa-metadata/v2/offers/search`) is rate-limited by an
  Azure Application Gateway; a burst of searches got the client blocked site-wide with 403.

What this means for the design:

1. **Delta delivery contradicts "on demand by bounding box".** A delta feed is only correct if it is
   consumed continuously from a known snapshot; it cannot be asked for a viewport. A Mobilithek
   provider therefore has to be a *background consumer* holding per-EVSE state in memory, with viewport
   queries answered from that state — the shape this ADR rejected for OCPDB, where the reason (an
   unreliable `last_updated`) does not apply. The in-process scale-out blocker gets firmer: two replicas
   would each hold their own subscription cursor.
2. **The EVSE-ID join is unchanged.** DATEX II refill points carry the EVSE-ID; the exact-match rule
   applies as-is.
3. **Overlap with MobiData BW.** OCPDB already re-publishes some of these feeds (`datex2_ecomovement`,
   `datex2_chargecloud`, `datex2_tesla`). Two providers answering for the same EVSE need a precedence
   rule.
4. **The machine certificate is a secret** under ADR 0002: mounted into the API container, never logged,
   never committed.

**Status (2026-09-28): blocked on organisation registration.** Unblocked 2026-10-02, see below.

## Mobilithek (L5, 2026-10-02)

The product owner's organisation (`de.joinside.EVMap`, registered as a sole proprietorship — the open question from
the survey is answered: it qualifies) was approved, so the Mobilithek moved from the v3 candidates to gap filler L5,
ahead of phase 8b. Checked against the *Technische Schnittstellenbeschreibung* v1.3.2 and the live catalogue while
signed in.

### What the source is

- **28 dynamic AFIR offerings** match `AFIR-recharging-dyn`, all DATEX II v3 as JSON, brokered, schema profile
  `AFIR-Recharging-Dynamic-01-00-00_Delta`, delta delivery on. Not found in the catalogue: IONITY, Aral pulse, Allego.
- **Licences per offering** (`standardLicense` in the offer metadata): CC0 for most; **CC BY 4.0** for EnBW,
  Eco-Movement and GLS Mobility; six (ENERANDO, Road, Grid & Co, Ampeco EDRI, ELU Mobility, msu m8mit) state only
  "free use / open data" without a standard licence. Five need the operator's approval of the subscription
  (ENERANDO, TC-Backend, GLS Mobility, msu m8mit, Eco-Movement); the rest are approved automatically.
- **Payload:** `messageContainer.payload[].aegiEnergyInfrastructureStatusPublication.energyInfrastructureSiteStatus[]
  .energyInfrastructureStationStatus[].refillPointStatus[].aegiElectricChargingPointStatus` carries, per charge point,
  `reference.idG`, `status.value` (13 values), `lastUpdated`, `operationStatus`, and optionally `energyRateUpdate`
  with a price **including an explicit `taxIncluded` and `taxRate`** — the field OCPDB drops (ADR 0022).
- **Transport:** `GET https://mobilithek.info:8443/mobilithek/api/v1.0/subscription/datexv3?subscriptionID=…`,
  mTLS with the organisation's machine certificate (PKCS#12, issued by the Mobilithek; the server itself presents a
  public Telekom certificate, so the JVM's default trust store suffices). `Accept-Encoding: gzip` is mandatory
  (406 without it); responses are always gzipped. 204 = buffer empty, 304 = nothing newer, 404 = subscription
  gone **or the operator's access quota exhausted**.
- **Delta replay is per package.** The broker keeps the last full package plus every delta after it. A request with
  `If-Modified-Since` far in the past returns the oldest package — by definition the full one — and each following
  request with the previous `Last-Modified` returns the next. Without the header it returns only the newest package,
  which for a delta feed is useless on its own.

### Decisions (product owner, 2026-10-02)

1. **Placement:** gap filler L5, now, before phase 8b.
2. **Pull, not push.** The API container polls each subscription; push stays an open point (below). Push was explained
   and deferred: it needs a public write endpoint whose callers are checked against the Mobilithek's certificate at
   the reverse proxy, a DATEX acknowledgement per package, and it still needs the pull replay after every restart.
3. **Precedence:** where two providers report the same EVSE-ID, the **newer `observedAt` wins**, whichever provider
   it comes from. This replaces the first-registered-wins rule in `AvailabilityService`, which ADR 0015 had left
   "deliberately simple until a second one exists" — the Mobilithek is that second one for Germany, and MobiData BW
   re-publishes some of the same feeds with a delay.
4. **Prices are not part of L5.** The dynamic feeds carry ad-hoc prices with an explicit VAT flag; they become a
   separate step under ADR 0022 once real data shows how many feeds set `taxIncluded`.
5. **Subscriptions:** all 28 dynamic offerings. The static counterparts (`AFIR-recharging-stat-…`) only if the data
   shows an operator using internal ids instead of EVSE-IDs in `reference.idG`.
6. **Attribution per feed:** each subscription is configured with its publisher, licence and offer URL, and every
   charge point is credited to the feed that reported it ("EnBW AG via Mobilithek", CC BY 4.0) — not to the platform.
   CC BY requires naming the data provider.

### How it is built

`availability.mobilithek` follows the provider recipe of the package documentation, but is the first provider whose
state is **filled in the background** rather than on request, because a delta feed is only correct when consumed
continuously from its snapshot (survey, point 1):

- `MobilithekProperties` (`evmap.availability.mobilithek`): the broker URL, the machine certificate as a PKCS#12
  file plus password (`MOBILITHEK_KEYSTORE_PATH`, `MOBILITHEK_KEYSTORE_PASSWORD`, never logged, never committed),
  poll interval (60 s), a per-cycle ceiling on packages per feed, `max-age` (72 h, as for France), and the feed table
  (`subscription-id`, `publisher`, `licence`, `url`). Without a certificate or without a subscription id the provider
  reports itself disabled and the scheduler does nothing — like OCM without a key.
- `AfirStatusJson` parses one package into "snapshot or delta" plus the charge points it reports, reading arrays and
  single objects alike. `status` maps onto `LiveAvailability` like OCPI (`available`; `charging`/`occupied`/
  `reserved`/`blocked`; `faulted`/`inoperative`/`outOfOrder`/`unavailable`; `unknown`), `planned`/`removed`/
  `outOfStock` are dropped, and an `operationStatus` of `notInOperation*` or `technicalDefect` overrides to
  `OUT_OF_ORDER`. The id is `reference.idG` through `EvseIds` — the join stays exact; ids that are not EVSE-IDs simply
  never match, and the share that looks like an EVSE-ID is logged per feed so decision 5 can be taken on numbers.
- `MobilithekAvailabilityProvider` polls every feed in turn (`@Scheduled`, fixed delay): follows `Last-Modified`
  until 304, a snapshot replaces the feed's state, a delta updates it, 204 empties it, 403/404 back the feed off for
  an hour (quota or subscription gone, WARN), anything else keeps the state for the next cycle. A feed not polled
  successfully for ten minutes reads as unknown. `fetch(bounds)` answers with the merged state of all feeds — the
  dynamic feed has no coordinates — and `AvailabilityService` keeps only the identifiers it asked for, as for France.
- `ChargePointAvailability` gained an optional per-entry `Attribution`; a provider that sets none is credited with
  its own, so MobiData BW and France are unchanged.

Privacy: the requests carry no user data at all — not even a viewport, unlike MobiData BW.

### Open points (L5)

a. **Push delivery** — worth it only if pull hits the operators' access quotas (404 in the logs) or one minute of
   delay proves too much. Options: (1) stay with pull; (2) push endpoint with client-certificate check at the proxy,
   pull only for the replay after a restart.
b. **Static AFIR feeds** — needed only if `reference.idG` is not an EVSE-ID for a relevant operator; the per-feed
   log line answers it after the first day.
c. **Mobilithek prices** — separate step under ADR 0022 (decision 4).
d. **Second API replica** — the in-process state now has a subscription cursor per feed; two replicas would each poll
   (doubling quota use) but stay correct. Unchanged blocker, see open point 4.

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

2. ~~Whether TomTom can be used at all~~ — no; see [TomTom](#tomtom-2026-09-28).

3. **How duplicate EVSE-IDs are handled.** 1.657 of the register's EVSE-ID occurrences are repeats of
   an ID used elsewhere in the same edition. Currently the first wins and the rest are counted and
   logged; whether that is right depends on whether they are publisher errors or genuinely shared
   infrastructure.

4. **When the availability poller moves out of the API container** — tied to the first day a second
   replica is wanted, and to whether Mobilithek's per-CPO subscriptions make the polling profile
   heavier than one fixed-delay refresh. The Mobilithek's delta delivery makes this firmer: see the
   survey above.

5. ~~Attribution of live sources in the app~~ — resolved 2026-09-28. dl-de/by-2.0 (MobiData BW) and
   Licence Ouverte (transport.data.gouv.fr) both require naming the source, and Lastenheft §5 asks for
   provenance per charge point. `AvailabilityProvider.attribution()` is now mandatory; the response
   carries `sources` (name, licence, url) for the station and `source` per charge point, and the
   detail screen credits them in the live section's footer — with the Lastenheft's "Kombination
   mehrerer Datenquellen" label and a per-charge-point source when more than one contributed. The
   names and licence titles are proper names sent by the backend; only the surrounding words are i18n.

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
- [schema-irve-dynamique](https://schema.data.gouv.fr/etalab/schema-irve-dynamique/) — the French dynamic schema, v2.3.0
- [Base nationale consolidée IRVE – données dynamiques](https://transport.data.gouv.fr/resources/84098) — the consolidated file
- [TomTom EV Charging Stations Availability API](https://docs.tomtom.com/ev-charging-stations-availability-api/documentation/ev-charging-stations-availability-api/ev-charging-stations-availability)
- [Mobilithek](https://mobilithek.info) — German NAP; *Technische Schnittstellenbeschreibung* v1.3.2
