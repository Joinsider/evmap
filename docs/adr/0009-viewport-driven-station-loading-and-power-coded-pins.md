# 9. Viewport-driven station loading and power-coded map pins

- Status: Accepted
- Date: 2026-07-29
- Deciders: Johannes Popp

## Context

ADR 0001 §4 closed with an explicit piece of future work: "panning the map does not
refetch. A deliberate 'search this area' affordance (`onMapCameraChange(frequency:
.onEnd)`) is left as future work." Using the app made the gap concrete. Stations were
fetched on the first location fix, on filter apply, and on the location button — always
for a hard-coded 20 km circle around the *device*, never around what the user was
looking at. Panning to the next city showed an empty map; zooming out showed the same
twenty kilometres of pins floating in a country-sized view. The map's most natural
gesture produced no data.

Two smaller problems were bundled into the same change because they touch the same
screen and the same payload:

**Every pin looked identical.** A green `bolt.car.circle.fill` for a 3.7 kW wallbox and
for a 350 kW HPC site. Charging speed is the single most decisive attribute when
choosing where to stop, and it was invisible until the user tapped through to the
detail sheet — one station at a time.

**`station.power` never resolved.** `String(localized: "station.power \(powerKw)")`
interpolates a `Double`, which makes Foundation build the key `station.power %lf`. Both
strings files declare `station.power %@`. The lookup missed, the resource system fell
through to the interpolated key, and the connector list showed a raw unlocalized string
with no `kW` unit. `StationFilterScreen` did the same thing correctly by formatting to a
`String` first, so the same resource worked in one place and failed in the other.

A permanent bottom bar reading "*n* stations" occupied a full-width safe-area inset for
a number nobody acts on.

## Decision

### 1. The visible rectangle is the query

`MapViewport` translates an `MKCoordinateRegion` into the centre/radius pair the
endpoint speaks, taking the circumcircle of the viewport so the corners are covered.
`MapScreen` reports camera changes with `.onMapCameraChange(frequency: .onEnd)` — the
camera settling once per gesture *is* the debounce, so no timer is involved.

`MapViewport.isCovered(by:)` decides whether a settled camera actually needs the
network: a viewport is covered when it did not drift more than 25 % of the loaded
radius, did not change scale by more than ±25 %, and did not cross the overview
threshold. Scale is checked independently of movement because it changes the cluster
grid and, across the threshold, the query itself. In-flight requests are cancelled when
a newer viewport supersedes them, so a fast pan cannot end with a stale response
winning by arriving last.

The startup camera is now an explicit region (Germany, ~700 km) rather than
`.automatic`, and `.task` seeds the first query from it. `.automatic` derives its
region from map content, and at launch there is none — the map would have had to load
stations to decide where to look, in order to decide which stations to load.

### 2. Zooming out trades completeness for the highpower backbone

Two mechanisms, both necessary:

- **Server-side ranking with a row limit.** `GET /api/v1/stations` takes a `limit`
  (default 500, max 2000; the client asks for 600) and orders by station power
  descending. A viewport that overflows the limit therefore keeps the *fastest*
  chargers rather than an arbitrary slice.
- **A power floor above the overview threshold.** Viewports wider than 1.2° of latitude
  send `minPowerKw ≥ 100`. The complete German set is 113k stations; at country scale it
  is neither drawable nor useful, whereas the ≥ 100 kW network is precisely what a
  driver scans a country-scale map for. The floor only ever raises the user's own filter,
  never lowers it.

`radiusKm`'s server-side ceiling rises from 100 km to 1000 km, and the client clamps to
the same value. Beyond a continent the circle stops describing anything actionable.

When a response fills the limit the map says so (`stations.truncated`) instead of
presenting a truncated set as complete.

### 3. Pins are clustered on an absolute grid, client-side

`StationClusterer` buckets stations into a lat/lon grid sized from the current zoom
(~14 cells across the visible height), placing each cluster on its members' centroid.
The grid is anchored to absolute coordinates, not to the viewport, so panning never
reshuffles cluster membership — only zooming does. Cell width is divided by
`cos(latitude)` to compensate for MapKit's projection stretching longitude.

Clustering runs on every camera change, including ones that do not refetch, so grouping
follows the zoom immediately rather than at network latency.

Tapping a cluster zooms to a quarter of the current span centred on it; tapping a single
station opens the detail sheet. Both go through MapKit's `selection` binding, which the
handler consumes so no pin stays highlighted behind a sheet.

### 4. Pin colour encodes charging speed on a five-step cold-to-hot ramp

`ChargingPowerTier` maps the station's power onto blue (< 22 kW) → green (22) → yellow
(50) → orange (150) → red (300+). The boundaries are the tiers a driver plans around and
are deliberately the same numbers as `ChargingPowerStep`'s slider positions, so a station
that passes the power filter lands in the tier the user selected rather than one below
it. A station with no reported rating is grey — a different statement from "slow", and it
must not be shown as the bottom of the ramp.

Colour is never the sole carrier of the information: pins expose the power in their
accessibility label, the detail screen names the tier in text next to the value, and a
compact legend capsule sits in the map's bottom-trailing corner.

### 5. `maxPowerKw` is a station-level value on the list payload

`StationSummary` gains `maxPowerKw`, computed in SQL as a `LEFT JOIN LATERAL` aggregate
over `master.charging_connector`. The lateral sits **outside** the filter join on
purpose: with a connector-type filter active, an aggregate over the filtered join would
report the maximum of the *matching* connectors, so narrowing the filter would silently
recolour pins. A pin's colour must describe the station, not the query.

Index `charging_connector_station_idx` is extended from `(station_id)` to
`(station_id, power_kw)` so the aggregate is index-only (changeset 004).

No new column and no ingestion change: the per-connector `power_kw` the adapters already
write is the only power the data supports. BNetzA's station-level
"Nennleistung Ladeeinrichtung" column is not parsed, and inferring a station rating any
other way would be guesswork.

### 6. Two localization fixes

`formattedPower(kW:)` formats the number to a `String` before interpolation, so the key
is `station.power %@` as declared. Every call site goes through it. The cluster label
does the same with its count. The station-count bar and its `stations.count %@` resource
are deleted.

## Consequences

### Positive

- Panning and zooming load data, which is what the gesture implies.
- Zooming out is now informative rather than empty: the HPC backbone appears at country
  scale and detail fills in on approach.
- Charging speed is readable at a glance, across a whole screen of stations at once.
- The connector list and the power slider finally show `kW`.
- The `20 km` constant is gone from the client; the map's own geometry is the source of
  truth for what is queried.

### Negative / accepted risks

- **Request volume rises.** Every settled camera outside the loaded area is a request,
  where previously there were three trigger points. `isCovered(by:)` absorbs the
  small-movement case, but a user panning across a country will issue a series of
  queries. There is no client-side response cache; a pan back to a previous area
  refetches it.
- **A wide viewport is a heavier query.** A 1000 km radius aggregates over a large slice
  of the table. The 100 kW floor cuts the row count hard in exactly that case, and
  `StationService`'s existing slow-query warning covers the rest — but the ceiling is set
  by judgement, not by measurement against a populated production database.
- **Top-N by power can hide a slow station in a dense city.** A viewport below the
  overview threshold holding more than 600 stations drops the weakest ones. A driver
  hunting a Schuko outlet downtown may have to zoom further in than feels necessary;
  `stations.truncated` tells them the set is incomplete but not what is missing.
- **The overview threshold is a visible discontinuity.** Crossing 1.2° of latitude
  changes the result set abruptly — sub-100 kW pins appear or vanish in one step rather
  than fading in.
- **Grid clustering is not centroid clustering.** Two stations either side of a cell
  boundary stay separate even when they are adjacent on screen. The absolute anchoring
  that buys pan stability is what causes this; the alternative reshuffles groups as the
  user pans, which reads as flicker.
- **The clamped radius decouples pins from the visible rectangle.** Above 1000 km the
  circle no longer covers the corners, so a world-scale view shows pins concentrated
  around the centre. Clustering makes this less jarring than it would otherwise be.
- **`maxPowerKw` is only as good as the ingested connector ratings.** Sources that omit
  `power_kw` produce grey pins, which the legend does not explain.

### Neutral

- `ChargingStationRepository.nearby` gains `radiusKm` and `limit` parameters. The
  protocol is still the only path from UI to backend, so the planned GraphQL swap is
  unaffected.
- `MapViewModel` no longer fetches from the `CLLocationManager` callback: a fix moves
  the camera, and the camera change loads. One trigger instead of two.
- Backend test coverage is unchanged, because the change is in SQL and the project has
  neither Testcontainers nor a Docker daemon available to exercise PostGIS. The new
  iOS-side logic (viewport geometry, coverage, clustering, tier boundaries, power
  formatting) is covered by `EVMapTests`.

## Alternatives considered

**A "search this area" button**, as ADR 0001 sketched. Rejected: it makes the user
responsible for a refresh the app can infer, and the button has to be dismissed or
ignored on every gesture. Automatic loading with a coverage check is the same request
volume in practice with none of the interaction cost.

**A bounding-box endpoint (`minLat`/`maxLat`/`minLon`/`maxLon`).** A better fit for a
rectangular viewport than a circle, and it would remove the corner overfetch. Rejected
for now because it is a second spatial query path to maintain alongside `nearby`, whose
radius form is also what a "stations near me" list would want. The circumcircle costs
about 27 % extra area — cheap compared to the contract change.

**Server-side clustering / tile aggregation.** The correct endgame for a map with 113k
points: aggregate per zoom level in PostGIS and ship cluster counts. Rejected as
disproportionate now — it needs a new endpoint, a tiling scheme, and cache invalidation
tied to ingestion, and client-side clustering over a 600-row cap delivers the same
picture at this data volume.

**Hiding stations entirely below a zoom threshold** ("zoom in to see stations").
Rejected: it makes the overview view useless precisely when a driver wants the
long-distance picture, which is the case the HPC network exists to serve.

**A continuous colour interpolation from power.** Prettier, but a pin's colour then
means nothing exact — 140 kW and 160 kW would be indistinguishable while sitting in
different planning categories. Discrete tiers can be stated in a legend; a gradient
cannot.

**Colouring by availability instead of power.** Availability is already carried by the
detail sheet and by a filter toggle, and the reported status is source metadata rather
than live occupancy (a v1 non-goal, Lastenheft §10). Power is the attribute that
actually varies usefully across pins.

**Denormalizing `max_power_kw` onto `master.charging_station`.** Faster than the lateral
aggregate and indexable for the ORDER BY. Rejected: it would put a master-data column
under `sync`'s write path for a query optimization the current data volume does not
demand. The covering index is reversible; a column plus its ingestion maintenance is not.

## References

- `evMap_ios/EVMap/EVMap/Features/Map/Domain/MapViewport.swift`
- `evMap_ios/EVMap/EVMap/Features/Map/Domain/StationAnnotation.swift`
- `evMap_ios/EVMap/EVMap/Features/Stations/Domain/ChargingPowerTier.swift`
- `evMap_ios/EVMap/EVMap/Features/Map/Presentation/MapViewModel.swift`
- `evmap_service/src/main/java/de/joinside/evmap_service/api/station/StationSpatialRepository.java`
- `evmap_service/src/main/resources/db/changelog/004-connector-power-lookup.sql`
- ADR 0001 §4 — the deferred camera-change refetch this record closes
