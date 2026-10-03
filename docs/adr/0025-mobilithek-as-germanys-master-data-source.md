# 25. The Mobilithek as Germany's master data source

- Status: Accepted 2026-10-03 — gap filler L6, implemented on `feature/sync-mobilithek` (#45)
- Date: 2026-10-03
- Deciders: Joinsider

## Context

Live status attaches to a charge point only on an exact EVSE-ID match (ADR 0015). The coverage report built on
2026-10-03 (ADR 0015, "Coverage report") measured what that means for Germany with the BNetzA register as its only
master data source:

- 118.234 German charge points stored, 33.971 of them with an EVSE-ID (28,7 %); 19.546 with a live status — **16,5 %**.
- The Mobilithek's live feeds carry 115.666 distinct EVSE-IDs; about 96.000 of them match nothing we store.
- Where we do store an operator's EVSE-IDs, they match almost completely (EnBW 787 of 805, Wirelane 2.518 of 2.611,
  Qwello 906 of 909). EWE has **no** EVSE-ID under its prefix in the register, so none of its 1.577 live charge
  points can ever show a status.

The gap is our master data, not the feeds. Every operator that publishes live status on the Mobilithek also
publishes a static AFIR feed (DATEX II v3 `EnergyInfrastructureTablePublication`): sites, stations, refill points
with EVSE-ID, connectors, power, address and coordinates. The owner decided to make those feeds the authority for
Germany, with the register as the fallback for everything they do not cover (2026-10-03, "all feeds at once").

### What the static feeds contain (all active ones fetched 2026-10-03)

- **31 static offerings subscribed** — the 11 of L5 plus 20 new ones (owner, 2026-10-03, licences accepted in the
  portal on the owner's behalf). 24 deliver data; five await the operator's approval (m8mit, GLS, TC-Backend,
  ENERANDO, Eco-Movement: 404 until then); eliso answers 422 (no brokered delivery) and K.u.L. Rudnick 204.
- **70.017 stations, 131.660 refill points, 128.904 distinct EVSE-IDs**; 69.988 stations in Germany, 23 in Austria,
  1 in Italy. 144 refill points without an EVSE-ID.
- **The broker holds only the latest snapshot per static feed** (`snapshotPull`; a request with its `Last-Modified`
  answers 304). Publishers refresh daily. There are no deltas to apply.
- **The shape varies by publisher**, within the one schema: JSON or XML regardless of the offering (ladenetz.de,
  ladebusiness: XML); the EVSE-ID in `externalIdentifier` typed `evseId` (EWE, eRound, vaylens, …) or as the refill
  point's own `idG` (EnBW, Tesla, chargecloud, Monta, EDRI); location and address on the site or only on the
  station; `FacilityLocation` or `facilityLocation`; coordinates as `coordinatesForDisplay` or
  `pointByCoordinates/pointCoordinates`.
- **Several feeds name the register's station id** on the station (`externalIdentifier` typed `stationIdBNetzA`):
  EWE all 768 stations, Wirelane 2.065 of 2.121, eRound 8.036 of 9.397, vaylens 4.016 of 10.406, SMATRICS all.
  EnBW sends the same number typed `operatorIdBNetzA` on 5.264 of 5.302 stations; it is the station id (verified:
  5.165 resolve to an existing `Ladeeinrichtungs-ID`).
- **Two feeds relay the same charge points**: e-clearing.net's static feed contains 2.033 EVSE-IDs of ladenetz.de and
  510 of ladebusiness.
- **Operator names are worse than the register's** on 27.460 of 48.557 matched stations: "ENBW" for "EnBW mobility+
  AG und Co.KG", "Ladenetz" (the platform) for "Stadtwerke Düsseldorf AG", "Qwello Germany" for "Qwello Deutschland
  GmbH".

### How the two sources line up (offline simulation against the register edition of 2026-09-01)

| Step | Mobilithek stations |
|---|---|
| linked by the register id the feed names | 16.235 |
| linked by a shared EVSE-ID | 9.973 |
| linked by position (≤ 30 m, the sync's existing rule) | 22.349 |
| no register entry → new station | 21.460 |

Position alone pairs badly: before the EVSE-ID step was added, 6.091 stations whose EVSE-IDs sit on one register
entry were paired with a neighbour, and 4.304 were created anew next to an entry carrying their exact EVSE-IDs
(median distance 0,2 m). Coordinates of the same station differ more than one would hope: of the stations linked by
register id, 10 % lie more than 67 m from the register's point.

Next to 7.248 Mobilithek stations a register entry would stay unclaimed — typically a site the register lists as
two entries and the operator as one station. 1.787 of them are provably the same charge points (all their EVSE-IDs
come from the Mobilithek), 4.585 have no EVSE-ID at all, 876 have EVSE-IDs the Mobilithek does not know.

Charge points with an EVSE-ID: **65.424 in the register edition → about 163.000** after the merge.

## Decision

### A new source, `sync.mobilithek`, the authority for Germany

`evmap.sync.authority` maps `DE: MOBILITHEK`. The BNetzA adapter keeps running: under the existing rule it maintains
every station the authority has not claimed and is only linked to the ones it has
(`PostgresStationIngestionRepository.mayUpdate`). One adapter reads every configured static feed; the feeds are a
table in `application-sync.yaml` (`evmap.sync.mobilithek.feeds`), credited per feed like the live ones.

Each AFIR **station** becomes one `SourceStation` — the register's granularity (one `Ladeeinrichtung`), and the level
the feeds attach the register id to. Its id is `<feed key>/<station idG>`; its charge points are the refill points,
keyed by EVSE-ID. Only sites in Germany are read (or without a country — the Mobilithek is Germany's access point);
the 24 foreign sites are skipped. An EVSE-ID already emitted by an earlier feed of the run is not emitted again, so
a relayed charge point is not stored twice; feeds are read in table order, operators' own feeds before platforms.

### Linking: exact first, position last

The ingestion resolves a record to an existing station in this order — every step generic, none names a source:

1. its own `(source, source id)` — every run after the first;
2. **links the record declares**: `SourceStation.links`, `(source, source id)` pairs of another source's records
   describing the same station. The Mobilithek adapter turns `stationIdBNetzA` (and EnBW's `operatorIdBNetzA`) into a
   link to the register's record; the register source's token is configuration (`register-source: BNetzA`), not code;
3. **a shared EVSE-ID**: the station that already holds most of the record's EVSE-IDs;
4. **position**, as before (≤ 30 m, same country).

Steps 2–4 skip stations the record's own source already maintains under another id. Without that, two stations of one
source within 30 m — two AFIR stations of one site, which share coordinates — would overwrite each other's charge
points on every run, the problem Switzerland and Spain solved by clustering. This changes nothing for sources whose
records never lie within 30 m of each other, and fixes the overwrite for the ones whose do.

### The register's labels stay

A source can declare that its labels do not replace existing ones (`SourceStation.namesStation = false`). The
Mobilithek does: a station it takes over keeps the register's operator name, which the operator directory and every
user's provider preferences are keyed by (ADR 0014) — owner, 2026-10-03. The display name follows the same rule,
because the feeds' names are no better: EWE names every station by a code (`000501`), LichtBlick 2.765 of 3.001
(`ENE_SN0000325`), eRound about half, EnBW and vaylens none at all. A station only the Mobilithek describes gets the
feed's labels: the operator's `legalName`, then `name`, then the feed's publisher — never an operator id (`DE*EWE`,
`DESTA`) and never the station's own name, which VW Group Charging writes into `legalName` for all 4.651 stations; the
station's or site's name where it contains a lowercase word, otherwise the operator, as the register falls back.

### Duplicates are hidden, not deleted

After every run, for each source in `evmap.sync.supersede` (today `MOBILITHEK`), a station the source does **not**
maintain, in a country where it maintains stations, is marked `master.charging_station.superseded_by` when

- all of its EVSE-IDs are on stations of that source (proven) — then by the one holding most of them, at any
  distance: the evidence is exact, and 630 of the 2.417 proven duplicates lie more than 30 m away; or
- it has no EVSE-ID at all and lies within 30 m of a station of that source (the same rule the ingestion already uses
  to say "same place") — then by the nearest.

A station with an EVSE-ID the source does not know is never hidden — but it loses the charge points whose EVSE-ID
sits on one of the source's stations, so no EVSE-ID is listed twice and a live status attaches once. The end-to-end
run (see "Implementation") found 1.064 such repeated EVSE-IDs before this rule. The register re-delivers them every
run and the step after the run removes them again. The mark is recomputed every run, so a station
comes back when the reason goes away. Map, route corridor and operator directory skip superseded stations; a
station's own page, favorites, comments and reports keep working, because nothing is deleted — deleting would cascade
into user data. (Owner, 2026-10-03: "proof + 30 m rule".)

### New stations are created

A Mobilithek station without a register counterpart is created: the register says itself that it is incomplete.
Duplicates whose coordinates differ by more than 30 m are accepted and measured after the first run. (Owner,
2026-10-03.)

### Shared Mobilithek client

Broker access (mTLS, gzip, cursor) and the DATEX helpers move from `availability.mobilithek` to a neutral package
`de.joinside.evmap_service.mobilithek`, used by both. `sync` and `availability` still do not reference each other;
they share this package as they share `sync.EvseIds`. The sync container gets the same certificate secrets as the API.

### Prices: next step, not this one

The static feeds carry ad-hoc prices — explicit gross (eRound, Monta, EDRI, GP JOULE, SMATRICS), net with a rate
(EnBW, PUMP), or no VAT statement (chargecloud) — mixed with per-minute and blocking fees. They are read in a separate
step under ADR 0022 **directly after this one** (owner, 2026-10-03), after analysing the per-minute components per
feed. Done in L6p (2026-10-03): `AfirPriceReader`, ADR 0022, "Gap filler L6p".

## Implementation (2026-10-03)

- `sync.mobilithek`: `MobilithekSourceAdapter` (reads each feed's latest snapshot, spooled to a temporary file),
  `AfirSiteReader` (one site at a time; XML turned into the same tree as JSON), `AfirSiteMapper` (tolerant accessors,
  links, labels, EVSE-IDs, connectors), `MobilithekSyncProperties` (31 feeds in `application-sync.yaml`).
- A feed the broker has nothing for (204), does not deliver to us (403/404/422) is skipped with a warning; a feed that
  cannot be read or parsed fails the source's run (ADR 0013 contains it). Reading on would let a platform feed emit
  the charge points the broken operator feed relays as stations of its own.
- `ConnectorTypes.fromDatex` maps the DATEX connector enumeration for Spain and the Mobilithek alike (moved out of
  `sync.es`). Power below 1 kW is unknown — VW Group Charging writes 0 W for every charge point.
- Ingestion: `SourceStation.links` and `namesStation`; resolution by own id, link, shared EVSE-ID, position, none of
  them matching a station the record's own source maintains; `StationIngestionPort.supersedeDuplicates`, called by
  `SyncJob` after all sources for the sources in `evmap.sync.supersede`; migration 012 adds `superseded_by`.
- API: viewport, route corridor and operator directory skip superseded stations (`StationSpatialRepository
  .NOT_SUPERSEDED`); a station's page still loads by id.
- Checked against every real package of 2026-10-03: 67.312 stations, 128.954 charge points (128.810 with EVSE-ID),
  18.027 stations linked by register id; the largest package (eRound, 121 MB) parses in 0,6 s.
- **End-to-end run** (throwaway test, 2026-10-03): the register edition of 2026-09-01 and all 24 real packages through
  the real parser and ingestion against PostGIS, twice. Register 116.443 stations in 322 s; Mobilithek 67.312 records
  in 184 s, 0 failed (the first attempt lost 14.936 to undated stations, now dated by their site or the fetch), 48.047
  linked to a register entry, 19.265 new. 7.479 stations superseded (26 s). Repeated EVSE-IDs involving a Mobilithek
  station: 3.527 before the shared charge points were removed, 0 after; the 919 that remain are repeated inside the
  register itself (one EVSE-ID on 69 rows) and were there before. Visible German charge points with an EVSE-ID:
  156.322 of 235.082 (66 %), against 65.424 of 209.136 (31 %) in the register. The second run created nothing and
  superseded the same 7.479.
- Website: the data sources page credits every publisher; the privacy page now names the Mobilithek (missing since
  L5).

## Consequences

### Positive

- Live status becomes possible for most German charge points that report one; EWE goes from 0 to all.
- Exact links (register id, EVSE-ID) replace position for 26.000 stations, including the ones position paired wrongly.
- The fix to same-source overwrites applies to every source.

### Negative / accepted risks

- About 21.000 new stations, some of them duplicates more than 30 m from their register entry. Measured after the first
  run; the mitigation (a wider radius for stations without EVSE-ID, say) would be a new decision.
- Addresses and coordinates of taken-over stations now come from the operators, whose data quality varies by feed.
- A feed that stops leaves its stations as they were — they are not handed back to the register.
- The sync container now holds the machine certificate.
- 5 subscriptions depend on the operators' approval; eliso's static feed is not reachable through the broker.

## Open points

1. **Prices from the static feeds** — done in L6p (2026-10-03), ADR 0022, "Gap filler L6p".
2. **eliso** answers 422 to the broker: ask the Mobilithek support how the offering is delivered.
3. **Duplicates beyond 30 m** — measure after the first run on the server (new stations within 100 m of an unclaimed
   register entry), then decide whether stations without an EVSE-ID warrant a wider radius.
4. **New stations of platform feeds carry the platform as operator** where the feed names no operator: e-clearing.net
   writes "Ladenetz" (2.282 stations in the end-to-end run). Accepted for now (owner: feed names for new stations);
   revisit if the directory shows it as a problem.

## References

- ADR 0012 (authority table), ADR 0013 (per-source isolation), ADR 0014 (operator directory), ADR 0015 (exact EVSE-ID
  join, coverage report), ADR 0022 (prices)
- Mobilithek, *Technische Schnittstellenbeschreibung* v1.3.2; AFIR static profile `AFIR-Energy-Infrastructure-01-00-00`
- Simulation: register edition `Ladesaeulenregister_BNetzA_2026-09-01.csv` against the static packages of 2026-10-03
