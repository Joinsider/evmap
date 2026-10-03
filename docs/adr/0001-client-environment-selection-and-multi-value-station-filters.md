# 1. Client environment selection and multi-value station filters

- Status: Accepted
- Date: 2026-07-28
- Deciders: Joinsider

## Context

Two unrelated problems surfaced together while making the iOS client usable against
the deployed backend, and they are recorded here as one record because they were
resolved in one change and share the same blast radius: the
`GET /api/v1/stations` contract.

**Backend endpoint selection.** `APIClient` hard-coded `http://127.0.0.1:8080` as
its default base URL, with a `UserDefaults`-backed `API_BASE_URL` override as the
only way to reach anything else. That default is correct exactly once — for a
developer running the Compose stack on the same machine as the simulator. Every
other consumer (TestFlight build, device build on the developer's own phone, a
release install) silently pointed at a loopback address that cannot resolve,
producing a connection error rather than a diagnosable misconfiguration. Shipping
required a default that is right for the common case without removing the
developer's local workflow.

**Station filtering.** The filter sheet exposed connector type as a free-text
field and minimum power as a decimal text field. Both push the burden of knowing
valid values onto the user: the connector field only matched if the typed string
was byte-identical to whatever the ingestion adapters happened to write into
`master.charging_connector.connector_type`, and it could express only one type at
a time. Drivers routinely accept more than one connector (a car with a CCS inlet
also charges on Type 2 AC), so single-value filtering does not match the domain.
Free-text power entry likewise admits values (`7.4`, `43`, `0`) that carry no
meaning against real-world charging tiers.

Related: the map showed two controls that both looked like "find me" — MapKit's
native `MapUserLocationButton` and a custom toolbar button — with different and
non-obvious behaviors.

## Decision

### 1. Resolve the API base URL from the target environment, not the build configuration

`APIEnvironment.baseURL` resolves in this order:

1. a `UserDefaults` value for `API_BASE_URL`, if present and parseable;
2. otherwise `http://127.0.0.1:8080` when `targetEnvironment(simulator)`;
3. otherwise `https://evmap.joinside.de`.

The discriminator is deliberately **simulator vs. device**, not `DEBUG` vs.
`RELEASE`. A debug build running on a physical iPhone cannot reach the developer
Mac's loopback interface, so keying on build configuration would produce a
default that is wrong precisely where it is hardest to notice. Simulator
processes share the host's network stack, which makes "is this a simulator" the
accurate proxy for "is loopback meaningful here".

The `UserDefaults` override stays first in the chain, so
`-API_BASE_URL http://192.168.1.5:8080` in a scheme's launch arguments still
points a device build at a LAN development server.

### 2. Model connector types as a closed client-side enum, sent as a repeated query parameter

`ConnectorType` is a Swift enum over the connector standards common in Europe
(Type 2, CCS, CHAdeMO, Type 1, Tesla, Schuko, CEE). `StationFilter.connectorType:
String` becomes `connectorTypes: Set<ConnectorType>`, presented as a multi-select
checklist.

The wire format is a repeated query parameter — `?connectorType=CCS&connectorType=Type%202`
— bound server-side by `@RequestParam(required = false) List<String> connectorType`
and matched with `AND lower(c.connector_type) IN (:connectorTypes)`.

Matching is now case-insensitive on both sides, where it was previously an exact
`=` comparison. No `SourceAdapter` implementations exist yet, so nothing in the
database currently pins the casing of `connector_type`; case-insensitive matching
avoids coupling the client enum's raw values to whichever casing the first
adapter happens to emit.

### 3. Constrain minimum power to a discrete ladder of real charging tiers

`ChargingPowerStep.values` is `[nil, 3, 11, 22, 50, 100, 150, 300, 400, 600]`,
driven by an index-based `Slider` with `step: 1`. Position 0 means "no power
filter" and replaces clearing the old text field. The values correspond to
recognizable tiers — domestic outlet, single/three-phase AC, early DC, and the
current HPC bands — rather than to a continuous range.

### 4. One location control on the map

MapKit's `MapUserLocationButton` is removed; the toolbar button absorbs its
behavior. `MapViewModel` publishes a monotonic `locationFixCount` that `MapScreen`
observes to recenter on a 20 km region, matching the 20 km query radius. The
single remaining button now requests authorization, recenters, and refetches.

## Consequences

### Positive

- A device or TestFlight build points at production with no configuration step,
  and the simulator keeps its zero-configuration local loop.
- Connector filtering can express a real vehicle's capability set, and the user
  cannot enter a value that matches nothing.
- Case-insensitive matching means the first `SourceAdapter` cannot break
  filtering through a casing mismatch alone.
- The map has one control per intent.

### Negative / accepted risks

- **The client enum is a closed set over open data.** Ingested connector types
  that fall outside the seven enum cases become unfilterable from the app even
  though they are stored and returned. This is acceptable while the enum covers
  the European long tail, but it makes the enum a maintenance point that must be
  revisited when adapters land.
- **The enum's raw values are now an API coupling.** `ConnectorType.rawValue` is
  what reaches the backend, so renaming a case is a wire-format change. The first
  `SourceAdapter` must normalize source data to these values (modulo case), or a
  server-side alias mapping must be introduced.
- **Spring splits a single comma-containing value into a list.** `List<String>`
  binding accepts both repeated parameters and `?connectorType=CCS,CHAdeMO`. No
  current connector name contains a comma; one that did would be silently split.
- **`lower()` forecloses a plain index on `connector_type`.** No such index
  exists today — `charging_connector` is indexed on `station_id` only — so there
  is no current cost. Any future index on that column must be a functional index
  on `lower(connector_type)` to remain usable by this query.
- **Power filtering can no longer express arbitrary thresholds.** A user wanting
  ">= 43 kW" must pick 22 or 50. This is intentional; the ladder is the point.
- **Manual refresh is narrower than it appears.** Stations reload on the first
  location fix, on filter apply, and on the location button — panning the map
  does not refetch. A deliberate "search this area" affordance
  (`onMapCameraChange(frequency: .onEnd)`) is left as future work rather than
  smuggled in as a side effect of this change.

### Neutral

- `README.md` no longer describes `API_BASE_URL` as the mechanism for reaching a
  deployed backend, since that is now the default; it is documented as an
  override.

## Alternatives considered

**Build-configuration-based endpoint selection (`#if DEBUG`).** Rejected: a debug
build on a physical device is a normal development activity and would have
inherited an unreachable loopback default. The failure is a generic connection
error, which is a poor signal.

**An `.xcconfig`-supplied `API_BASE_URL` per configuration.** More conventional
and more flexible, but requires the Info.plist plumbing and a build-settings
change for a project that currently uses `GENERATE_INFOPLIST_FILE` with no
custom config files. The compile-time branch is a smaller change with the same
result for the two environments that exist; this remains the natural upgrade
path if a staging environment appears.

**Comma-joined connector types in a single parameter.** Would have avoided the
`List<String>` binding change, but pushes escaping onto the client and makes
values containing commas unrepresentable rather than merely fragile.

**Free-text connector filter with server-side fuzzy matching.** Keeps the client
open to unknown connector types, but leaves the discoverability problem
unsolved — the user still has to guess — and trades a client-side enum for
server-side matching heuristics that are harder to reason about.

**Continuous power slider.** Rejected: interpolated values like 187 kW imply a
precision the data does not support, and a continuous control makes the common
tiers harder to hit than a snapping one.

## References

- `evMap_ios/EVMap/EVMap/Core/Networking/APIEnvironment.swift`
- `evMap_ios/EVMap/EVMap/Features/Stations/Domain/ConnectorType.swift`
- `evMap_ios/EVMap/EVMap/Features/Stations/Domain/StationFilter.swift`
- `evmap_service/src/main/java/de/joinside/evmap_service/api/station/StationSpatialRepository.java`
- `evmap_service/src/main/java/de/joinside/evmap_service/sync/SourceAdapter.java` — the extension point whose first implementation will exercise the connector-value coupling
