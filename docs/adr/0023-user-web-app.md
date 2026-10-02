# 23. User web app

- Status: Accepted 2026-10-02 — part 8a (map and station, read-only) implemented on `claude/kind-tesla-0rm3p2`;
  8b (taking part: comments, reports, blocks, favorites, station reports) open
- Date: 2026-10-02
- Deciders: Johannes Popp

## Context

Roadmap phase 8 brings EVMap to the browser: map, station details, comments, favorites and account on the
Angular skeleton of phase 1 (ADR 0018), route planning on the web afterwards. The map was decided with ADR 0018:
**MapKit JS**, the same look and place search as on iOS, free up to 250.000 map views and 25.000 service calls a
day, its token signed by the backend.

The phase was pulled forward. The next phase in order was 5b (charging cards), whose station display and card
management live in the iOS app; the session that started this phase runs in the cloud without an iOS simulator.
Phase 8 is the one phase that needs no iOS work at all.

Almost every endpoint the web app needs exists already, because iOS uses them: `GET /api/v1/stations`
(viewport query, ADR 0009/0014), `/stations/{id}`, `/{id}/availability` and `/availability` (ADR 0015),
`/{id}/charge-points` (prices, ADR 0022), `/{id}/comments`, `/operators` (ADR 0014), and the account endpoints of
phases 2 and 3. What is missing is the MapKit JS token.

## Decisions

Agreed with the product owner on 2026-10-02, one question at a time.

### Phase 8 comes before 5b

Order now: 8 → 5b → 6 → 7 → 9. The product owner chose this over splitting 5b into a backend part and a later iOS
part, over writing 5b's iOS code without a simulator, and over planning 5b only. Recorded in the roadmap.

### Phase 8 is cut into 8a and 8b

- **8a — read**: the map with MapKit JS, place search, the station filter with provider preferences, and the
  station screen with live availability, ad-hoc prices and comments, read-only.
- **8b — take part**: writing comments, reporting comments and blocking authors (ADR 0020 named this for phase 8),
  favorites with the device-or-account rule of ADR 0021, and station error reports.

Rejected: one large PR, and 8a including comment writing.

### Scaling is checked before the public launch, not before the build

The roadmap asked to reassess scaling (ADR 0015's in-process cache, a second API replica) before phase 8. There is
no monitoring data in the building session. Decision: build 8a now; **before the web app is announced publicly the
product owner checks response times and load in Uptime Kuma** (ADR 0019). The web app sends the same queries iOS
does — more of them, not different ones — so nothing in 8a depends on the outcome.

### The backend signs short-lived MapKit JS tokens

Apple now also offers static, domain-restricted MapKit JS tokens from the developer portal. Asked again with that
option, the product owner kept the decision of ADR 0018: the backend signs.

- `GET /api/v1/map/token` answers `{token, expiresAt}`: an ES256 JWT with `kid` (the Maps key id), `iss` (team id),
  `iat`, `exp` (30 minutes), `scope: mapkit_js` and `origin` (default `evmap.joinside.de`).
- Public like the station reads: the map has to work signed out. The `origin` claim binds the token to our domain;
  a token copied out of a browser stops working within half an hour.
- Off when the key is not configured: the endpoint answers 404, the web map says it is not available. Like a
  sign-in provider with blank credentials (ADR 0018).
- `Cache-Control: no-store`; the token is never logged.

## Design (8a)

**Backend** — `api.map`: `MapKitProperties` (`evmap.mapkit.*`: team id, key id, private key, origin, lifetime),
`MapKitTokenSigner`, `MapTokenController`; `SecurityConfiguration` permits `GET /api/v1/map/token`.

**Web** — the map is the start page.

- `core/api`: `EvmapApi` gains the station reads, the operator directory and the map token; the wire types follow
  the iOS domain types.
- `core/map`: `MapEngine` is the seam to MapKit JS (`MapKitEngine` loads `mk/6/mapkit.core.js` on demand, with the
  token from the backend), the web counterpart of the seams iOS keeps around MapKit. Tests use a fake.
- `features/map/domain`: the iOS logic, ported as pure functions with the same numbers — `viewport` (circumcircle
  query, 1.2° overview with a 100 kW floor, the 25 % coverage rule, 600 rows), `clusters` (absolute grid, 14 rows),
  `power-tier` (blue → red at 22/50/150/300 kW, grey for unknown), `station-settings` (the `AppSettings`/
  `StationFilter` split and the lenient decoding of ADR 0014), `prices` (groups and wording of ADR 0022).
- Settings live in the browser's `localStorage`, like `UserDefaults` on iOS: device-only, never sent to the backend
  except as the query they produce, every access wrapped so a blocked storage only costs persistence.
- Place search uses MapKit JS's `Search.autocomplete`, debounced (300 ms, at least three characters) because it
  draws on the daily service quota. It moves the map and nothing else (ADR 0011); the queries are not logged and
  not stored.
- The station screen is a panel beside the map (a sheet below it on narrow screens) under `/station/:id`, so a
  station can be linked and reloaded.

**nginx** — the CSP opens what MapKit JS needs and nothing more: `script-src https://cdn.apple-mapkit.com
'wasm-unsafe-eval'`, `img-src`, `connect-src` and `worker-src` for `https://*.apple-mapkit.com` and `blob:`.

## Consequences

- The web app loads a script from Apple's CDN: visitors' IP addresses reach Apple, and so do the texts typed into
  the place search. That is new for the web client and is recorded in `docs/privacy/data-processing.md`; the
  privacy policy has to say it.
- One more key on the server (the Maps key), to keep out of logs and to rotate.
- MapKit JS cannot run in unit tests; the map page is tested against the `MapEngine` fake, and the real map is
  checked by hand once the key exists.
- Every map view counts against Apple's quota of 250.000 a day. A token reload every 30 minutes is not a map view.

## What phase 8a built (2026-10-02)

**Backend** (`api.map`): `MapKitProperties` (`evmap.mapkit.*`, env `MAPKIT_TEAM_ID` → defaults to `APPLE_TEAM_ID`,
`MAPKIT_KEY_ID`, `MAPKIT_PRIVATE_KEY`, `MAPKIT_ORIGIN`), `MapKitTokenSigner` (ES256 via Nimbus, `typ: JWT`, `kid`,
`iss`, `iat`, `exp`, `scope`, `origin`; the key is parsed at startup, so a bad one fails the deployment, never echoing
it), `MapTokenController` (`GET /api/v1/map/token` → `{token, expiresAt}`, `Cache-Control: no-store`, 404 without a
key). `SecurityConfiguration` permits the GET only. Both compose files pass the variables. Tests:
`MapTokenControllerTests` (signature, every claim, escaped newlines, 404, malformed key),
`SecurityConfigurationTests` (public GET, no POST). Backend: 521 tests, the 84 PostGIS ones skipped locally without a
container runtime and run on CI.

**Web** (`evmap_web`):
- `EvmapApi` gained `stations`, `station`, `stationAvailability`, `availabilityInBounds`, `chargePoints`,
  `comments`, `operators`, `mapToken`, with the wire types in `models.ts`.
- `core/map`: `MapEngine` (create, autocomplete, resolve) and `MapKitEngine`: fetches the token first (so a backend
  without a key fails before Apple's script is downloaded), loads `mk/6/mapkit.core.js`, `mapkit.init` with an
  `authorizationCallback` that refetches on expiry, loads `map,annotations,services`, gives up after 15 s.
  Annotations are diffed by id, so a reload replaces only changed pins; a selection is consumed at once.
- `features/map/domain`: `viewport`, `clusters`, `power-tier`, `station-settings`, `prices`, `availability`,
  `format` — the iOS logic with the same thresholds, each with Vitest tests.
- `MapPage` (start page): viewport loading with coverage check and cancellation, the 100 kW overview floor, the
  600-row notice, live counts per pin, cluster tap zooms to a quarter span, pin colour legend, "map not available"
  notice. `PlaceSearch`, `FilterPanel` (connectors, power steps, operational only, the provider list with the global
  switch and the directory search, reset with confirmation), `StationPanel` under `/station/:id` (address, operator,
  power tier, service state, "ab" price, live occupancy with age, EVSE-IDs and credit, connectors, price groups with
  disclaimer and credits, comments read-only, data sources). A station opened by link is brought into view.
- `StationSettingsStore`: `localStorage` key `evmap.stationSettings.v1`, lenient parsing, works without storage.
- The placeholder home page is gone; nginx's CSP opens Apple's MapKit hosts. 100 web tests; production build for
  `de` and `en` with every string translated.
- Checked in a headless Chromium against the production build with a mocked API: map notice, filter panel, station
  panel in German and English, light and dark, desktop and phone width. The real map was not seen: there is no Maps
  key in the building session (the real MapKit script was loaded with a fake token and ended, as it should, on the
  "not available" notice).

### Deviations from the plan

- `availabilityOnly` filters on the client, as on iOS: the backend query has no such parameter.
- Dates in the English build use Angular's `en` (US) format; there is one English build, not one per region.
- Comments show a hint that writing them is possible in the iPhone app for now; 8b replaces it.

### 👤 Steps for the product owner

1. Create a Maps ID and a MapKit JS key in the Apple developer portal and set `MAPKIT_KEY_ID` and
   `MAPKIT_PRIVATE_KEY` in `deploy/.env` (`docs/operations/web-map.md`).
2. Roll out the API and web images of this release; check `/api/v1/map/token` and the map on `/de/`.
3. Look at the real map once on desktop and phone (pins, cluster tap, search, station panel) — the part no test in
   this phase could see.
4. Before announcing the web app: check response times and load in Uptime Kuma.
5. Name MapKit JS (Apple) in the published privacy policy (`docs/privacy/data-processing.md`).

## Open points

1. **Rate limiting of the token endpoint.** Signing is local and cheap, so none is built. If abuse shows up, the
   option of ADR 0018 applies: (a) `limit_req` in the web container's nginx (recommended), (b) a bucket in the API.
2. **Route share links on the web.** `/route?…` still shows the notice page of ADR 0017; opening a route belongs to
   the web route planner after phase 8.

## References

- ADR 0009 (viewport loading, pins), ADR 0011 (search moves the camera only), ADR 0014 (settings, provider
  preferences), ADR 0015 (live availability), ADR 0017 (route links), ADR 0018 (web client, MapKit JS), ADR 0020
  (moderation on the web), ADR 0021 (favorites), ADR 0022 (prices), ADR 0002 (logging)
- Apple: "Loading the latest version of MapKit JS", "Creating a Maps token", "Creating and using tokens with Maps
  Server API" (claims, `scope`)
- Lastenheft §11 (Web-Client); `docs/roadmap.md`, phase 8
