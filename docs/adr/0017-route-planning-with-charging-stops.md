# 17. Route planning with charging stops (v2)

- Status: Accepted 2026-09-29 — stage 1 (manual planner) implemented in roadmap phase 4 (`feature/phase-4-manual-route-planner`, 2026-10-01); stages 2–4 not started; stages scheduled in `docs/roadmap.md`
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

## Phase 4 decisions (2026-10-01): the manual planner

Answers of the product owner when phase 4 started; the rest of this ADR is unchanged.

### A route starts from a place info card, not from a toolbar button

There is no planner button in the map toolbar. A route begins where a person already is in the app:
they search for something, or tap any place on the map (a town, a POI, a charging station). That opens
an **info card** in the manner of Apple Maps: name, address, category, distance, and the actions
**route from here**, **route to here** and **add as waypoint** (the latter once a route exists). The planner
sheet appears only after one of these actions and is then filled from the same card, so there is one way in
and one planner state. Charging stations get the same actions in `StationDetailScreen`.

Consequence for ADR 0011: the address search no longer "moves the camera and nothing else". Picking a
suggestion still moves the camera, and now also selects the place and shows the card. The viewport
loading path stays the only fetch trigger.

### Saved places are a free, named list on the device

Any place from the search can be saved under a name of the person's choosing (home, office, holiday flat)
and is then offered in the waypoint picker next to recent searches and favorites. Device-only, never
sent to the backend, no part of the account export (there is nothing server-side to export), and not
cleared by "reset settings" (like recent searches, ADR 0014). Favorite stations (ADR 0021) are also
selectable as waypoints. Rejected: only home/work (too narrow) and favorites only (no way to remember
a non-station place).

### The current route and a list of saved routes are kept on the device

The route being planned, including its stops and the station details it needs, is cached so it stays
readable without a network (Lastenheft §11). In addition a person can name and save routes ("Gardasee").
Both are device-only (ADR 0017 *Persistence, sharing, offline*). Planning again needs a network.

### Share link: a universal link on the web domain

`https://evmap.joinside.de/route?…` carries the waypoints (coordinates, names, dwell times, route options)
in its query string; nothing is stored on the server. The app opens it through the Associated Domain
that phase 1 already set up for the sign-in callback. Without the app the link reaches the web container,
which shows a plain notice until the web app (phase 8) can open the route itself. The web container
writes the `applinks` entry for `/route` into its `apple-app-site-association` (built, see below); 👤 it
has to be redeployed, and the app reinstalled once for the new entitlement. No custom URL scheme.

### Defaults chosen without a question

- "Route to here" starts at the device location; if it is unknown or denied, the start stays empty and is
  filled in the planner. "Route from here" leaves the destination empty.
- At most 8 waypoints between start and destination, which stays inside Google Maps' URL limit for the
  whole-route handoff.
- `POST /api/v1/stations/along-route` is public like the `GET` station endpoints and is bounded by the request:
  at most 500 polyline points, a corridor of at most 25 km, at most 200 stations back. It needs an explicit
  `permitAll` for that one POST (`SecurityConfiguration` only opens `GET /api/v1/stations/**`), and stays
  exempt from CSRF as an anonymous call (ADR 0018). The polyline is never logged above `.debug` and never stored.
- Stations are pre-filtered in a corridor of 5 km by default; exact detour minutes are computed for the ten
  stations nearest the road (MapKit throttling, see *How detours are computed* below).
- Break suggestions use MapKit POI categories restaurant, café, bakery, restroom and hotel, searched at several
  points along the route.
- The research of open point 1 (vehicle data, Open EV Data) and point 2 (tariff sources) runs next to the
  implementation and ends as dated sections in this ADR. It produces no code in phase 4.

## What phase 4 built (2026-10-01)

**Backend.** `POST /api/v1/stations/along-route` in `api.station` (`StationController`, `StationService`,
`StationSpatialRepository`, `AlongRouteQuery`). The body is `{route: [{latitude, longitude}], corridorKm,
connectorType[], minPowerKw, excludeOperator[], includeOperator[], limit}`; the answer lists
`{station, distanceAlongRouteKm, distanceToRouteKm}` in driving order. Bounds: 2–500 points, corridor
0.1–25 km (default 5), at most 200 stations (default 200), the usual operator-list caps. The corridor test
runs against `ST_Subdivide`d pieces of the line (64 vertices each) so the GiST index sees small boxes; a
bounding box of one long diagonal route would otherwise hand back half the country. Over the limit the
strongest chargers are kept, as in the viewport query, and the answer is then put in driving order.
Filter SQL is shared with `findNearby`. `SecurityConfiguration` opens exactly this one POST
(`permitAll`); it stays a read. The route is never logged, not even at debug (only the point count).
Tests: `AlongRouteQueryTests` (PostGIS), `AlongRoutePayloadTests`, `SecurityConfigurationTests`.

**iOS `Features/Routing`.**
- `Domain`: `RouteWaypoint`/`RouteSlot` (a row of the planner, possibly still empty), `RouteOptions`,
  `PlannedRoute` (whole geometry, legs, positions and times along it), `RouteStation`/`RouteStopCandidate`,
  `PolylineSimplifier` (Douglas–Peucker to ≤ 400 points), `RouteShareLink`, `DetourEstimator`,
  `SavedPlace`/`SavedRoute`/`StoredRoutePlan`, `PlaceSelection`, `BreakSuggestion`.
- `Data`: `RouteProviding` + `MapKitRouteProvider` (`MKDirections`, legs joined for waypoints, alternatives
  for a plain start → destination), `NearbyPlacesProviding` + `MapKitNearbyPlacesProvider` (points of
  interest), `RouteHandoff` (Google Maps URL, Apple Maps items), `FileRoutingStore` (JSON in Application
  Support). `ChargingStationRepository.stationsAlongRoute` is the REST call.
- `Presentation`: `RoutePlannerViewModel` (owned by `EVMapApp`), `RoutePlannerScreen` (stops with drag and
  drop and stays, options, alternatives, stations by detour or along the route, breaks, handoff, share,
  save), `WaypointPickerScreen`, `SavedRoutingScreen`, `PlaceInfoCard`, `RouteSummaryBar`.
- `MapViewModel` has a mode (`.viewport` / `.route`). In route mode the pins are the stations along the
  route, camera changes load nothing, and `clearRoute()` returns to the viewport.
- The station screen has the same route actions as the info card. Share links arrive through `onOpenURL`
  (`RouteShareLink.parse`); the `applinks:evmap.joinside.de` entitlement is added.

**Web container.** `40-apple-app-site-association.sh` now writes `applinks` for `/route` next to
`webcredentials`, and `/route` serves a static notice page without the app.

**How detours are computed.** The corridor query pre-filters. For the ten stations nearest the road MapKit
is asked for two travel times each — from a route point 2 km before the station's position to the station,
and from the station to a point 2 km after it — and the detour is their sum minus what the route itself
takes over those 4 km (proportional to distance within the route). The rest are ranked by an estimate from
their distance to the route and shown with `≈`. Requests go three stations at a time, and a throttled
MapKit stops the refinement and says so. The ADR's "when a station is selected" refinement is **not**
built: the list shows the exact value for the ten nearest only.

**Break suggestions** are loaded on request (a button), at intervals of two hours of driving, at most four
searches of a 3 km radius for restaurant, café, bakery, restroom and hotel. A station's surroundings are
searched on request from its row menu (400 m).

### Deviations from the plan

- Planner entry is the info card, not a toolbar button (owner's decision, see above); the search therefore
  also selects a place (ADR 0011 update).
- The planner is a sheet that can sit at half height, with a summary bar over the map once it is closed, not
  a persistent panel: a second sheet (the station screen) cannot open over it, so a station picked in the
  planner closes it first.
- Detour refinement is for the ten stations nearest the road, not the ten best by detour, and not on
  selection (open point 5).
- No long-press to drop a pin: a tap on a town, a place of interest or a station, and the search, are the
  ways to pick a place. A free spot on the map without a feature can not be selected yet.
- Apple Maps handoff is built as designed (start → destination, or leg by leg) but **not yet tried on a
  device with iOS 26**; the same holds for the Google Maps URL and the universal link (👤 below).

### 👤 Steps for the product owner

1. Redeploy the web container (new `apple-app-site-association` and `/route` page), check
   `https://evmap.joinside.de/.well-known/apple-app-site-association` (`docs/operations/sign-in-providers.md` §4 step 5).
2. Build and install the app once on a device (the `applinks` entitlement is new), then open a shared route
   link from Messages.
3. Device test: plan a route with a waypoint, check Apple Maps (leg by leg) and Google Maps (whole route) on
   iOS 26, put the phone in flight mode and reopen the plan, save and open a route, reorder stops.

## Research notes (2026-10-01, ADR 0017 open points 1 and 2)

A desk review, no code. It narrows the questions for phases 5 and 6; it does not answer them.

**Vehicle data (open point 1).** The [Open EV Data dataset](https://github.com/open-ev-data/open-ev-data-dataset)
is community-maintained, licensed **CDLA-Permissive-2.0** (free to use, modify and share, so compatible with
shipping it inside EVMap's own database, with attribution to be checked), and its schema covers gross and net
battery capacity, AC power and onboard charger, DC peak power, voltage class and **charging curves**, connectors,
and rated consumption (WLTP, EPA and others). That is level (a) of the open point and in part level (b). What is
not known yet: how many models it holds, how complete the curves are for European models, and how fast it is
updated (110 commits at the time of the review). **Next step for phase 6:** load the dataset and measure
coverage against the 50 best-selling models in Germany before building an own database.

**Tariff sources (open point 2).** Charging-card tariffs are exchanged between operators through **OCPI**
tariffs and are not public. **AFIR** (Regulation (EU) 2023/1804) obliges operators to publish static and dynamic
data, free of charge, through the national access points, and prices at 50 kW and above must be per kWh; that is
the most likely open source of ad-hoc prices, and it is the same route as the live data of ADR 0015 (Mobilithek is
blocked on registering an organisation). The review found **no open, machine-readable list of charging-card
tariffs**. The fallbacks of the open point stand: a hand-maintained master table, or user-entered tariffs with a
plausibility check. **Next step for phase 5:** check which national access points publish prices yet (Germany's
Mobilithek, France's transport.data.gouv.fr) and in which format.

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
Points 1 and 2 have a first research note above (2026-10-01) and stay open for phases 6 and 5. Point 2 was
answered for ad-hoc prices by the source check in ADR 0022 (German OCPI tariffs via MobiData BW, French register
free text); charging-card tariffs still have no open source and remain with phase 5b.

4. **Selecting an arbitrary spot on the map.** The info card opens for a search hit and for a tapped town,
   place of interest or station. A free spot without a map feature can not be chosen. Options: (a) a long
   press that drops a pin and reverse-geocodes it, as Apple Maps does (recommended, small); (b) leave as is.
5. **Exact detour for more than ten stations.** Options: (a) leave at ten and compute one more when a station
   is opened (recommended); (b) compute all, which MapKit's request limit rules out for long routes.

## References

- Lastenheft §4, §10, §11 (v2)
- ADR 0002 (logging and privacy), ADR 0009 (viewport loading), ADR 0011 (address search),
  ADR 0014 (provider preferences), ADR 0015 (live availability)
