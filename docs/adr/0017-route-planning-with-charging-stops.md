# 17. Route planning with charging stops (v2)

- Status: Accepted 2026-09-29 — nothing implemented yet; stages scheduled in `docs/roadmap.md`
- Date: 2026-09-29
- Deciders: Johannes Popp

## Context

Route planning is listed in Lastenheft §4 as an extension to prepare for and excluded from v1 in
§10. The goal is a planner in the spirit of ChargingTime: enter a start, a destination and
optional waypoints, see charging stations along the route filtered by the user's settings and
preferred networks, and stop at them — or at places that are not chargers at all. A real
turn-by-turn navigation may follow later and must not be designed out.

Much of the groundwork exists: the SwiftUI `Map`, `AddressSearchProviding` (addresses *and*
POIs, ADR 0011), the reserved `ProviderPreference.preferred`/`.avoided` (ADR 0014), and a GiST
index on `master.charging_station.location`, which makes "stations near a line" the same kind of
PostGIS query as today's radius search.

## Decision

### Scope and release

- The feature belongs to **v2**. §10 stays as it is for v1; the Lastenheft gets a v2 section when
  this ADR is accepted.
- It is **not coupled to GraphQL**. The new endpoints are REST behind `ChargingStationRepository`,
  and GraphQL is an optional item at the end of the roadmap (decided 2026-09-29).
- It ships in **stages**, each releasable on its own. Their position among the other features is set
  in [`docs/roadmap.md`](../roadmap.md) (phases 4–7 and 9; charging cards and prices at the station come
  before automatic stops, "cheapest route" after them, and the web app comes before CarPlay):
  1. **Manual planner** — start, destination, waypoints; stations along the route ranked by
     detour minutes; stops added by hand; handoff to Apple Maps / Google Maps; share link; route
     options (avoid tolls, avoid motorways, alternative routes); saved places.
  2. **Automatic charging stops** — vehicle profiles and a planner that proposes stops.
  3. **Charging cards and prices** — "cheapest" as a planning goal.
  4. **CarPlay** as a charging app (EV-charging entitlement, not navigation).
- Optional **v3**: live vehicle data (state of charge from the car).
- **Turn-by-turn navigation is out of scope** for all four stages. It stays a later option (see
  Open points).

### Routing engine: MapKit, behind a seam

`MKDirections` computes routes on device, free of charge. It sits behind a `RouteProviding`
protocol in `Features/Routing/Data`, for the same reason as `AddressSearchProviding`: the
presentation layer must not know who answers. This is also the way out to turn-by-turn: MapKit
offers no guidance API to third parties, so that stage would swap in a self-hosted Valhalla
(e.g. with the open-source Ferrostar SDK) without touching `Presentation`.

Accepted MapKit limits: routes have only a start and an end, so waypoints are separate legs that
the app joins; there is no elevation profile, no ferry avoidance, and requests are throttled.

### Stations along the route, ranked by detour minutes

- A new read-only endpoint in `api.station` — `POST /api/v1/stations/along-route` — takes a
  **simplified** polyline (Douglas-Peucker on the client, a few hundred points), a pre-filter
  corridor and the usual `StationFilter` parameters. It uses `ST_DWithin` against the line and
  sorts by position along the route (`ST_LineLocatePoint`), not by power as the radius query does.
- **Detour minutes** are the user-facing filter. Because MapKit throttles, they are computed in
  two steps: the corridor query pre-filters, then the exact detour (extra route legs) is
  computed only for the best candidates, or when a station is selected.
- `MapViewModel` gets an explicit mode ("viewport" vs "route"), so that the along-route result and
  the viewport loading from ADR 0009/0011 do not overwrite each other.

### Waypoints

A waypoint is an address, a POI, a charging station or a saved place, entered through the existing
address search. The order is fixed as entered and can be changed by drag and drop; the app does not
optimize it. A waypoint may carry a **dwell time** ("1 h lunch"), which feeds arrival times and,
from stage 2, charging near that waypoint. Break suggestions (restaurants, hotels, toilets) come
from MapKit POI search at several points along the route. "Chargers with amenities nearby" is
derived the same way, because the station data has no amenity fields.

### Automatic stops (stage 2) are planned on the backend

The client sends the simplified route and the vehicle parameters, and the backend returns the
stops. The planning goal can be chosen: **fastest arrival**, **cheapest** (from stage 3) or
**by filters/preferred networks**. Planning values (minimum arrival charge, target charge per stop,
minimum station power) are defaults in the vehicle profile that can be overridden per route.

### Vehicle data

- **Several vehicle profiles**, each able to carry its own connector and power filter.
- Source: a **vehicle database** (model → capacity, consumption, charging curve), with the state of
  charge entered by hand. A **public dataset** is preferred where its licence and coverage allow.
  Otherwise the database is **our own**, and improved continuously:
  - users can **submit** a missing or corrected vehicle. A submission is user data under the
    Lastenheft's separation and only becomes master data once it is accepted;
  - users can **create a vehicle manually** by entering its parameters, kept on the device as
    their own profile.
- **Live data from the car** (state of charge via Smartcar or manufacturer APIs) is not planned.
  It is an optional **v3** candidate.

### Charging cards and prices (stage 3)

A **curated list** of common charging cards and their tariffs, kept as master data, plus
**tariffs the user enters**. The curated list should be kept up to date **automatically** where a
source allows it; how is still to be researched (Open point 2). Ad-hoc prices are added once an
API supplies them (Mobilithek is blocked on registering an organisation, see ADR 0015).

### Live availability

**Display only** on proposed and chosen stops; it does not influence planning. A status now says
little about arrival in two hours, and coverage is partial (ADR 0015). The route bounding box is
never queried: a route spans half a country, so live status is asked for per stop.

### Persistence, sharing, offline

- Planned routes and saved places are stored **on the device only** (like recent searches, ADR 0011),
  never on the backend.
- **Share link:** the waypoints are encoded in the link itself; nothing is stored on the server.
- **Offline:** the planned route including stops and station details is cached locally and
  readable without a network; planning again requires one.

### Handoff to navigation apps

- **Apple Maps:** its URL and `MKMapItem` handoff reliably carries only start → destination. The
  app therefore hands over **either start → destination or leg by leg** (the next stop as the
  destination), and returns to the plan between legs.
- **Google Maps:** the **whole route** with its waypoints, through the `waypoints=` parameter of
  its directions URL, as far as its waypoint limit allows. Longer routes fall back to leg by leg.
- Verify both against iOS 26 during stage 1.

### Privacy

Start and destination are often home and work. The route polyline sent to the backend is treated
like a coordinate under ADR 0002: never logged above `.debug`, never persisted, and redacted from
request labels. `docs/privacy/data-processing.md` gets a row for it, and a row for the vehicle
data once stage 2 exists.

## Consequences

### Positive

- Stage 1 needs no new server infrastructure and no paid API.
- The `RouteProviding` seam keeps turn-by-turn possible without a rewrite of the UI.
- `preferred`/`avoided` finally mean something, without changing the stored settings format.

### Negative / accepted risks

- MapKit throttling caps how many detours can be computed exactly; the ranking uses the
  corridor query as a fallback.
- A constant consumption figure is noticeably off on winter motorway trips. Stage 3's realistic
  model (elevation, speed, temperature) would need a different routing engine.
- A curated tariff list goes stale unless someone maintains it.
- An own vehicle database with user submissions needs a moderation step, or wrong values reach
  every user planning with that model.

## Open points

1. **Vehicle parameters and data source** — which parameters a vehicle has, and how detailed:
   a) minimal: usable capacity, average consumption, max DC/AC power, connectors;
   b) plus a charging curve (power over state of charge);
   c) plus consumption by speed and temperature (only useful with the stage-3 model).
   Also to evaluate: whether Open EV Data (or another public dataset) covers this set under a
   compatible licence, before an own database is built.
2. **Automated tariff updates** — to research: whether any source publishes charging-card tariffs
   in machine-readable form (roaming platforms, price-comparison APIs, AFIR price obligations),
   and at what cost. Fallbacks: a hand-maintained master table, or user-entered tariffs
   aggregated with a plausibility check.
3. **Live availability during turn-by-turn** — deliberately undecided. Revisit with the product
   owner when turn-by-turn is scoped.

Resolved 2026-09-29: live vehicle data → optional v3; handoff → see *Handoff to navigation apps*.

## References

- Lastenheft §4, §10, §11 (v2)
- ADR 0002 (logging and privacy), ADR 0009 (viewport loading), ADR 0011 (address search),
  ADR 0014 (provider preferences), ADR 0015 (live availability)
