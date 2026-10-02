# 22. Prices at the station

- Status: Accepted 2026-10-01 — part 5a (operator per charge point, ad-hoc prices) implemented on
  `feature/phase-5a-prices-at-station`; 5r (net/gross check per operator) implemented on
  `feature/phase-5r-vat-basis`, its open points 1 and 2 on `claude/vibrant-cannon-0qy3ym` (2026-10-02);
  5b (charging cards) open
- Date: 2026-10-01
- Deciders: Johannes Popp

## Context

Phase 5 of the v2 roadmap shows at each station what charging there costs (Lastenheft §11, "Ladekarten und
Preise"). It builds on the tariff research of phase 4 (ADR 0017, open point 2) and carries a prerequisite from
the Spanish gap filler: the operator per charge point (ADR 0012, "Spain (L4)", open point 10).

Before anything was decided, every source was checked live on 2026-10-01:

- **Germany has real ad-hoc prices, openly.** MobiData BW's OCPDB, the live-status provider of ADR 0015
  (OCPI 3.0, dl-de/by-2.0, no key), publishes 1.026 OCPI tariffs at `/tariffs`, referenced by `tariff_ids` on
  97.427 connectors. They are the AFIR feeds of ecoMovement (672 tariffs, 64.927 EVSEs), chargecloud (352, 25.362)
  and EnBW (2, 11.495). They are joined to a charge point by its EVSE-ID, exactly like the live status.
- **France has only free text.** The IRVE register's `tarification` column is filled on 54.975 of 223.610 charge
  points with 579 distinct values: prose, links, "Inconnu", simple prices ("0,29€ / kWh", "59 cts/kWh"), a JSON
  blob of one operator (DRIVECO) and an OCPI-like generated format of two operators (EASYCHARGE, Citeos). Its
  `gratuit` flag is `true` on 290 rows.
- **Open Charge Map** has `UsageCost` free text on 16.697 of 67.165 sites in the eleven crawled countries,
  community-maintained, of unknown age, in five languages; ~1.000 read as a price.
- **Spain (DATEX II), Switzerland (OICP) and the BNetzA register** carry no prices.
- **Charging-card tariffs** have no open machine-readable source (as found in ADR 0017).

Two defects of the German data were found on the way, both in OCPDB rather than in the feeds:

1. **The VAT basis is lost.** DATEX II states per price whether tax is included (`taxIncluded`); OCPDB keeps the
   rate and drops the flag ([binary-butterfly/ocpdb#278](https://github.com/binary-butterfly/ocpdb/issues/278),
   open). OCPI defines `price` as excluding VAT, but the values do not all behave like it: for 133 tariffs
   (28.592 connectors) the price × 1,19 lands on whole cents (Lidl 0,4622 → 0,55 €, Shell 0,6639 → 0,79 €), for
   463 (26.714) the value is already a round amount (Allego 0,64), 73 (12.984) are neither, and chargecloud's 352
   (25.910) carry no rate at all.
2. **Time prices are per minute, not per hour.** OCPI's `TIME` price is per hour. OCPDB's DATEX importer maps
   DATEX `pricePerMinute` onto `TIME` without converting (`datex2_v3_5_json_static_mapper.py`, release 2.16.2),
   and the values (0,06 / 0,10 / 0,20 after minute 30, 60 or 240) only make sense per minute.

## Decisions

Agreed with the product owner on 2026-10-01, one question at a time.

### Phase 5 is cut into 5a, 5r and 5b

- **5a** (this branch): operator per charge point and ad-hoc prices in API and iOS.
- **5r**: the net/gross check per operator against official price pages (see below), its own phase directly
  after 5a, so 5a ships with the mechanism and one verified entry.
- **5b**: charging cards (curated list plus own tariffs) and "what does it cost with my cards".

### Operator per charge point: a column on `master.charge_point`

A nullable `operator_name` on `master.charge_point`, written by every adapter that knows the operator per
charge point; where it is empty, the station's `operator_name` applies. The name stays the identity, as in the
operator directory (ADR 0014). Rejected: an operator table with ids (a larger migration and a rebuild of every
adapter and of the directory, for data — logo, hotline — nobody has asked for).

### German ad-hoc prices are read like live status

On demand, cached briefly in process, never written to `master.*`, attached only on an exact EVSE-ID match, shown
with source and time. AFIR counts the price among the dynamic data, operators change it at any time, and the
existing MobiData client and EVSE-ID join are reused. Rejected: a nightly import into master data (up to a day
old, and a second write path next to `StationIngestionPort`).

### Free text is read only where a price is unambiguous — when in doubt, no price

The owner asked for an analysis of everything the files hold first (done above) and then a recognizer that reads
clear patterns and otherwise shows nothing. A price that is shown wrongly is worse than none. For France:

- A text is accepted only if it is **consumed completely** by known parts: an energy price (`€/kWh`, `ct/kWh`,
  `cts/kWh`, `c€/kWh`, `e/kWh`), a session fee (`… € la session`, `à la connexion`, a leading `2€ +`), a time fee
  (`€/min`, `€/mn`), the qualifiers `TTC`, `HT`, `pour les non-abonnés` and a short type label (`AC`, `DC`,
  `HPC`, `Charge normale :`, `Bornes rapides :`). Time windows, "après 3h", prose and links are rejected.
- Contradictions are rejected: a value below 1 in cents (`0,35cts/kWh`), `€/kW` instead of `€/kWh`, a bare number
  without a unit, more than three decimals on an amount without a VAT basis, an energy price outside 0,05–1,50 €.
- **No VAT statement means gross**: French consumer prices must be shown TTC, and a plain "0,29 € / kWh" is that.
  **Explicit `HT` is converted** × 1,2 (French standard rate for charging).
- **The generated OCPI-like format of EASYCHARGE and Citeos** ("entre 08:00 et 20:00 : 0.30916667€ par kwh de
  charge, …") is net without saying so: it maps one-to-one onto OCPI price components, which are defined without
  VAT, and 7.525 of its 9.627 charge points land on whole cents (or, with six or more decimals, on a tenth of a
  cent) after × 1,2. It is converted **only where that check holds** for its single energy price. Round values
  (1.100, could be either), values without that evidence (729) and texts with several different energy prices
  (273) are rejected. Its time and idle fees are not shown; the price carries "further fees possible" instead.
- DRIVECO's JSON (1.601 charge points) is read structurally: `energyPrice` and `fixedPrice`; its overstay fee
  is shown as "further fees possible".
- `gratuit = true` reads as free, unless the text names a price, then neither is shown.

Result on the 2026-10-01 file: ~10.000 charge points from plain texts, 7.525 from the generated format, 1.601
from DRIVECO.

**Open Charge Map gets no prices** (owner, 2026-10-01): little yield, unknown age, unchecked community input.

### German tariffs: shown where the VAT basis is established

A German OCPI tariff is shown when its gross energy price is established by, in this order:

1. an explicit tax-included flag, once OCPDB delivers one (#278) — then it wins over everything below;
2. **evidence**: the price has more than two decimals and price × (1 + rate) lands on whole cents within the
   rounding of the given decimals — then it is net;
3. **the operator table** `evmap.pricing.mobidata.net-price-operators` / `gross-price-operators`, maintained by
   hand from official price pages. Seeded with **Allego: net** (owner checked 0,64 → 0,76 €/kWh).

Everything else shows no price. Filling the table for the other operators is phase 5r.

### Time fees are shown per minute, and the unit is watched automatically

Shown as €/min, because that is what OCPDB delivers (see Context). Because OCPDB may fix the mapping at any
time, the unit is detected on every refresh of the tariff list, **per source**: the median of all non-zero time
prices of that source decides. Real per-minute fees lie between 0,01 and 1 €/min, real per-hour fees between
0,60 and 60 €/h, and no source mixes them, so a median between 0,005 and 1 means per minute, one between 1 and
60 means per hour (divided by 60), anything else means unknown — then time fees show only "time-based fees from
minute X" without an amount. A change of the detected unit for a source is logged at WARN. A single time fee
outside the band of its source's unit loses its amount the same way.

### Display: station screen per charge point group, and a "from" price at its top

The station screen lists the ad-hoc price per group of charge points with the same operator, connector and
power ("0,59 €/kWh · Startgebühr 1,50 € · ab Min. 240: 0,10 €/min"), with "Ad-hoc-Preis laut Betreiber", the
source and its age; the operator is shown per group where it differs from the station's. The lowest energy
price of the station ("ab 0,49 €/kWh") sits at the top of the screen, under name and address, so it is the first
thing seen after tapping a station, even at the sheet's medium height. No prices on the map pins and no price
filter in 5a; both belong with charging cards (5b).

The owner first chose the info card (`PlaceInfoCard`) for the "from" price; that choice rested on a wrong
description in the question — a tapped station opens the station screen directly, the info card is only for
search hits, towns and places of interest. Asked again on 2026-10-01, the owner chose the top of the station
screen. Rejected: the route planner's station list (one request per station or a new batch endpoint) and an info
card in front of the station screen (changes the phase 4 flow and adds a tap).

## Design (5a)

- **Schema** `011-charge-point-operator-and-price.sql`: `operator_name` on `master.charge_point`;
  `master.charge_point_price` (one row per charge point, `ON DELETE CASCADE`): currency, gross energy price,
  session fee, time fee per minute, free, further fees, `observed_at`. Master data, written only by sync through
  `StationIngestionPort` (the charge points of a station are replaced as a whole, so are their prices).
- **sync**: `SourceChargePoint` gains `operatorName` and an optional `SourcePrice`. IRVE, MITERD and DIEMO fill
  the operator per charge point; IRVE parses `tarification`/`gratuit` through `IrvePriceText`.
- **pricing** (new package, the counterpart of `availability`): `PriceProvider` (MobiData BW is the first,
  `pricing.mobidata`), `PricingService`, an in-process cache (tariff list and per-area EVSE → tariff mapping,
  15 minutes), `PriceProviderRegistrationTests`. It reuses `availability.GeoBounds` and `availability.Attribution`
  and `sync.EvseIds`, and writes nothing.
- **API** `GET /api/v1/stations/{id}/charge-points` (public, like availability): every charge point with
  EVSE-ID, operator, connectors and its price (live tariff first, else the register's), the cheapest energy price
  of the station, and the sources to credit. The station payload itself is unchanged.
- **iOS**: `ChargingStationRepository.chargePoints(stationID:)`, a price section on the station screen and the
  "from" price at its top; all strings German and English.

## Consequences

- France and the larger German networks get a price where it is certain; everything else stays honestly blank.
- The hand-kept operator table goes stale if an operator changes its publishing pipeline; the explicit flag
  replaces it once OCPDB ships #278.
- Prices are only as fresh as the cache (15 minutes) and as complete as the EVSE-ID join (ADR 0015 measured
  14,5 % of live EVSEs resolved); coverage in Germany is therefore partial.
- Adds one public read endpoint and no personal data (`docs/privacy/data-processing.md` unchanged).

## What phase 5a built (2026-10-01)

**Schema.** `011-charge-point-operator-and-price.sql`: `master.charge_point.operator_name` (with a partial index on
the rows that have one) and `master.charge_point_price` (gross energy price, session fee, time fee per minute,
free, further fees, `observed_at`; cascades with its charge point). Both are master data, written only by `sync`.

**sync.** `SourceChargePoint` carries `operatorName` and a `SourcePrice`. IRVE, MITERD and DIEMO set the operator per
charge point; the ingestion stores it only where it differs from the station's, so the column stays sparse (on the
2026-10-01 IRVE file 817 charge points differ). `sync.irve.IrvePriceText` reads `tarification`/`gratuit`: on the
live file **20.516 of 166.499 emitted charge points get a price and 363 are free**; every one of the 257 distinct
accepted texts was read back by hand. A per-minute fee above 0,50 € is rejected (0,79 €/min would be 47 € an hour).

**pricing.** `PriceProvider`, `PricingService`, `ChargePointInventory`, `PriceCache`, and `pricing.mobidata`
(`MobiDataBwPriceProvider`, `OcpiTariffs`, `MobiDataPricingProperties`). Checked against the live API: 1.026
tariffs, all three feeds detected as per-minute, 454 charge points priced in the Stuttgart area.

**API.** `GET /api/v1/stations/{id}/charge-points` (public): charge points with EVSE-ID, operator (falling back
to the station's), connectors and price, the cheapest energy price and the live sources to credit. Operator
filters (`operator`, `excludeOperator`, `includeOperator`) and `GET /operators` count the operators of a bundled
station's charge points: a station matches any of its operators and is hidden only when all of them are.

**iOS.** `ChargingStationRepository.chargePoints(stationID:)`, `StationChargePoints`/`AdHocPrice`/`PriceGroup`/
`PriceFormatter` in `Features/Stations/Domain`, `StationPriceSection` (one row per operator, plugs and price, each
with its own "Stand" date; "further fees possible"; charging-card disclaimer; credits) and the "ab … €/kWh" line in
`StationInformationSection`. Verified end to end in the simulator against a local API with live MobiData tariffs
(EnBW Korntal 0,70 €/kWh + 0,12 €/min from minute 120; Allego via the operator table 0,76 €/kWh).

**Tests.** Backend 525 (`IrvePriceTextTests`, `OcpiTariffsTests`, `MobiDataBwPriceProviderTests`,
`PricingServiceTests`, `PriceProviderRegistrationTests`, `ChargePointControllerTests`, bundled-operator filters in
`StationQueryTests`, ingestion of operator and price); iOS `PriceTests`, `StationDetailPriceTests`, render tests.

### Deviations from the plan

- The "from" price sits at the top of the station screen, not on the info card (question corrected and asked again,
  see the display decision above).
- The station's own operator is not repeated on its charge points; `NULL` means "the station's operator".
- The price age moved from the section footer to each row, because one station's prices can be months apart
  (Allego's tariff dates from April, a register price from September).

### 👤 Steps for the product owner

None to deploy: no key, no account. Device test after the rollout: a German station with live tariff (EnBW or Lidl),
a French one with a register price, and a Spanish bundled station with two operators.

## Phase 5r decisions (2026-10-02)

Before asking, the whole OCPDB was read on 2026-10-02 (100.948 locations, 1.029 tariffs): of 95.970 charge points
with a single tariff, 26.774 got a price from the arithmetic evidence, **63.356 had none because their operator was
unchecked** (551 operators), 5.799 have a tariff `OcpiTariffs` does not understand. The gap is concentrated: the
top 30 operators hold 69 % of it, the top 50 79 %, the top 100 91 %. Three findings shaped the questions:
chargecloud's feed carries **no VAT rate on any tariff** (25.583 of the 63.356), some round-looking values are
**gross hidden as net × 1,19** (Mainova 0,6426 = 0,54 × 1,19), and several "operators" are **platforms** whose site
hosts set their own price (ChargePoint, smopi, Spirii, Ecotap, 50five, Backcharge).

Agreed with the product owner, one question at a time:

- **Scope: the top 50 operators** by unchecked charge points (~50.000, 79 %). The rest follows when needed. The
  research runs in parallel, one small agent per five operators (owner's request).
- **When an operator counts as checked:** an ad-hoc price on the operator's **own official page** matches a feed
  value exactly, as gross or as net × 1,19 rounded to the cent — then it is entered. A **third-party source**
  (comparison site, press, forum screenshot) only if it is **at most two months old**, and such entries are
  **spot-checked by the owner** before they ship. Near misses are not entered.
- **No VAT rate in the feed:** for an operator checked as net, **19 % is assumed for German charge points**; without
  a table entry such a tariff still shows no price.
- **Platforms** are entered only if they declare officially that all their published prices are gross (or net);
  otherwise they are skipped and listed below.
- **Keeping the table true:** every entry carries its **check date and source**. If the feed contradicts an entry
  (listed gross, but one of the operator's tariffs is demonstrably net by the arithmetic evidence), the entry is
  **suspended** for that operator and logged at WARN. Entries are re-checked after six months.
- **Rounding** (asked after the research found it): every amount of a table operator is shown **in whole cents**,
  as its price page charges it — Mainova publishes 0,6426 (= 0,54 × 1,19) and charges 0,64 €. E-Werk Mittelbaden
  shows AC unrounded (47,60 ct = 0,40 × 1,19) but DC rounded (65,00 ct for 0,6545) and therefore stays out. Rejected:
  leaving amounts unrounded (Mainova would show 0,643 €) and a rounding setting per entry (would still not fit E-Werk).

## What phase 5r built (2026-10-02)

**Research.** Ten agents, five operators each, searched the official price pages of the top 50 (brief: exact match
only, third-party sources at most two months old, platforms only with an official statement). Every accepted source
was fetched again and compared by hand. Result: **14 operators entered** (TankE, N-ERGIE, Berliner Stadtwerke, deer,
Stadtwerke Stuttgart, EnW Bonn/Rhein-Sieg, Mainova, BS|ENERGY, Allgäuer Überlandwerk, SWLB, ecowerk and ovag gross;
IONITY and Hochtief Ladepartner net), **9 platforms skipped** (ChargePoint, MENNEKES, Spirii, Ecotap, energielösung,
smopi, FIRMENLADEN, 50five, Backcharge), **27 unclear** — mostly operators that publish no ad-hoc price at all, and a
few near misses. No third-party source met the two-month rule, so no spot check is pending. Charge points priced via
the table: 2.064 → 15.576; all German charge points with a tariff that show a price: 30 % → 44 %. On the live data
of 2026-10-02 no entry is contradicted. Per-operator evidence and the re-check procedure:
`docs/operations/price-basis-operators.md`.

**Code.** `VatBasisTable` (own class: dated entries, validation at startup, suspension, entries due for re-check)
replaces the two name lists; the property is now `evmap.pricing.mobidata.vat-basis` (operator, basis, checked-on,
source) plus `recheck-after` (P6M). `OcpiTariffs.contradicts` and `looksGross` are the self-check, which
`MobiDataBwPriceProvider` runs over all locations of an area *before* reading any price, so no price of a suspended
operator leaves that area. `Location.assumedVatRate()` gives 19 % for German locations, used only for a net entry
whose tariff names no rate. Table amounts are rounded to whole cents (`Basis.cents`).

**Tests.** Backend 535 (`VatBasisTableTests`, `ShippedVatBasisTableTests` binding the real `application.yaml`,
contradictions, cent rounding and the assumed rate in `OcpiTariffsTests`, suspension end to end in
`MobiDataBwPriceProviderTests`). No iOS change: the API payload is unchanged.

### Deviations from the plan

- Allego stays as the product owner checked it on 2026-10-01; it was not researched again.
- The cent rounding was not planned; the research found Mainova's sub-cent values and the owner decided.

### 👤 Steps for the product owner

Nothing to configure. Review the evidence list in the PR; after the rollout, a device test at a station of a newly
listed operator (e.g. Berliner Stadtwerke 0,55 €/kWh AC, TankE 0,49 €/kWh, IONITY 0,72 €/kWh). Watch the API log for
`contradicts its VAT basis entry`. Re-check due **2027-04-02** (startup warns).

## Phase 5r open points (2026-10-02)

Both open points of 5r were put to the product owner on 2026-10-02, one at a time.

**Upstream fixes (open point 1): issue texts, posted by the owner.** OCPDB 2.16.2 (2026-09-28) still drops
`taxIncluded` (#278 open, no comment) and still maps DATEX `pricePerMinute` onto OCPI `TIME` unconverted (the export
mapper even maps `TIME` back to `pricePerMinute`). `docs/operations/ocpdb-upstream-issues.md` holds a comment for #278
with our measurements and a new issue on the time unit; the owner posts both, because they appear under their name.
Nothing in EVMap waits for them: an explicit flag already wins, and the time unit is detected per feed.

**Operator table (open point 2): a second round for operators 51–100.** The gap was ranked again from the whole OCPDB
(unchanged: 63.356 charge points, 551 operators); the first round had covered ranks 1–51, so this round took 52–101
(50 operators, 7.315 charge points, ~12 % of the gap), with the 5r brief and ten agents. Every candidate was then
fetched again by hand *and* checked against the feed's structure, which refuted one agent verdict: Mer Germany's
0,58 sits on 400 kW points while its other tariffs are proven net, so a gross entry would have been suspended at once.

Two questions came out of the research and were decided:

- **The entry settles the VAT basis, not the feed's content.** Where an entered operator's feed differs from its
  price page — an outdated amount (Weinheim DC 0,65 € in the feed, 0,69 € on the page), a value the page names as the
  app price (Kiel 0,46 €), a start fee or a blocking-fee minute — the feed is shown as delivered, as "Ad-hoc-Preis
  laut Betreiber" with its date: it is the operator's own AFIR publication and may be station-specific. The owner
  first did not follow the question; it was asked again with these two examples. Rejected: a list of confirmed page
  prices per entry (only matching tariffs shown) and leaving such operators out.
- **Unrounded gross pages are entered rounded.** eins energie publishes 58,31 / 66,64 ct gross (49 / 56 net × 1,19);
  it is entered and shown as 0,58 / 0,67 €. Rejected: a per-entry "unrounded" switch, and leaving it out like
  E-Werk Mittelbaden (which mixes rounded and unrounded prices and stays out).

**Found on the way: a tariff that contradicts itself.** Some chargecloud tariffs carry a zero and a non-zero time fee
from the same minute as two unrestricted elements (Allgäuer Überlandwerk: 0,00 and 0,05 €/min) — a time of day lost
on import. `OcpiTariffs` dropped zero fees before checking for two prices at one moment, so the fee was shown from
the first minute. It now counts the zero, and such a tariff shows no price (297 charge points of Allgäuer
Überlandwerk, 114 of MWEnergy, 125 of Lingen). This is the existing rule ("two prices for one moment: uncertain"),
not a new one.

**Result.** 16 operators entered (Münster, Electra, Ingolstadt, JOLT, Kiel, eins, Bamberg, Bocholt, öPA, Dachau,
Bruchsal, PGG Baden-Baden, Landsberg, Weinheim, Lingen, MWEnergy; Electra, JOLT and Dachau net), 10 platforms skipped,
24 unclear. Charge points priced via the table: 15.576 → 17.351; German charge points with a tariff that show a price:
44,2 % → 46,0 %. No entry is contradicted on the live data. Evidence per operator and what differs from the page:
`docs/operations/price-basis-operators.md`, "Second round". Backend tests: the self-contradicting fee in
`OcpiTariffsTests`; `ShippedVatBasisTableTests` binds the 31 entries.

## Open points

1. **Upstream fixes in OCPDB.** Decided 2026-10-02: (a) — the texts are drafted in
   `docs/operations/ocpdb-upstream-issues.md` (a comment on #278 with our measurements, a new issue on the
   `pricePerMinute` → `TIME` unit); the owner posts them. Still unfixed in OCPDB 2.16.2. Closed on our side once posted.
2. **Operator table beyond phase 5r.** Decided 2026-10-02: (b) — a second round for operators 51–100, see
   "Phase 5r open points" above. Done: 16 more operators entered. Operators 101–551 (~9 % of the gap) and the 70
   unclear or platform operators of both rounds remain; options for later: (a) a further round when monitoring or
   users show a need, and the re-check on 2027-04-02 (recommended); (b) ask LadeVerbundPlus for its shared ad-hoc
   price, which would settle several members at once.
3. **Charging cards (5b)** — curated list (who maintains it, admin UI or repository file), own tariffs (device
   or account), price filter and map display.

## References

- ADR 0012 ("Spain (L4)", open point 10), ADR 0014 (operator directory), ADR 0015 (live availability, EVSE-ID
  join), ADR 0017 (research notes, open point 2), ADR 0002 (logging)
- OCPDB issue [#278](https://github.com/binary-butterfly/ocpdb/issues/278); OCPI 2.2.1 / 3.0 Tariffs module
- Lastenheft §11; `docs/roadmap.md`, phase 5
