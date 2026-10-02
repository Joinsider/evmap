# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

EVMap is an EV charging station app for Europe: a native iOS/SwiftUI client (`evMap_ios/`) and an
Angular web client (`evmap_web/`, admin area today, user web app later) backed by a Spring Boot 4
service (`evmap_service/`). Data is merged from the Bundesnetzagentur Ladesäulenregister
(Germany, authoritative) and Open Charge Map (international/community), deduplicated by geo-distance +
address fuzzy matching. See `Lastenheft_EV_Ladestationen_App.md` for the full German-language product
requirements spec — check it before making architectural decisions, since several constraints (schema
separation, REST-behind-abstraction, i18n) come directly from it.

## Commands

Backend (`evmap_service/`):

```sh
cd evmap_service
./mvnw package                 # build (runs tests)
./mvnw test                    # run tests only
./mvnw test -Dtest=AccessTokenServiceTests   # run a single test class
```

The SQL tests (`*RepositoryTests`, `StationQueryTests`, `ChargePointDirectoryTests`) start
`kartoza/postgis` through Testcontainers (`support.PostgisDatabase`) and need a container runtime;
without one they are skipped locally and fail on CI. Podman works if its Docker socket is exposed.

Full local stack (Postgres + API + sync container):

```sh
cd evmap_service && ./mvnw package
cd .. && docker compose up --build
```

Copy `.env.example` to `.env` first and set `JWT_SECRET` (long random secret); `APPLE_CLIENT_ID` is optional
for local dev. The sync container has no public port and runs on the `sync` Spring profile
(`application-sync.yaml`), triggering ingestion on a fixed 24h delay.

Web (`evmap_web/`, Node 24.15+):

```sh
cd evmap_web
npm ci
npm start              # dev server :4200, German build, /api proxied to 127.0.0.1:8080
npm test               # Vitest, once
npm run build          # production build, one bundle per locale (de, en)
npm run extract-i18n   # after changing texts; then add the English target in src/locale/messages.en.xlf
```

iOS (`evMap_ios/EVMap/EVMap.xcodeproj`): open and build in Xcode. To point the app at a non-default
backend, set the `API_BASE_URL` launch argument/user default (dev default is `http://127.0.0.1:8080`).
Sign in with Apple requires the capability enabled for `de.joinside.EVMap` in the Apple Developer portal.

CI (`.github/workflows/ci.yml`, ADR 0016) runs `./mvnw verify`, the web tests and production build, and the iOS unit tests
(`xcodebuild test -scheme EVMap -only-testing:EVMapTests`, Xcode 26.6 on `macos-26`; on PRs only when
`evMap_ios/**` changed), then a `sonar` job analyses all three into the single SonarQube Cloud project
`Joinsider_evmap` (root `sonar-project.properties`) from the other jobs' artifacts. When upgrading the
Xcode project or raising the deployment target, move the `XCODE_VERSION` / `SIMULATOR` pin with it.

## Backend architecture

Two deployables share one PostgreSQL/PostGIS database but are built from the same Maven module via
separate Dockerfiles (`Dockerfile` for the API, `Dockerfile.sync` for the ingestion job) — this is
intentional prep for later splitting into fully separate services (see Lastenheft §6) without a code
rewrite. Do not blur this boundary:

- **`api` package** — stateless REST API (`station`, `comment`, `auth`, `security` sub-packages). Only
  reads master data, only writes user data.
- **`sync` package** — ingestion job. `SourceAdapter` is the interface every external data source must
  implement, normalizing into `SourceStation`. `StationIngestionPort` is the _sole_ write
  boundary into master data (`PostgresStationIngestionRepository` implements it). There must be no direct
  reference from `sync` into `api` repositories/entities, or vice versa.

**Read `sync/package-info.java` before touching the sync package** — it holds the layering rules
(`sync.<source>` → `sync` ← `sync.support`) and the three-type recipe for adding a source. Nothing in
`sync` names a source; `SyncJob` takes whatever adapters the component scan found.

Ingestion commits in batches (`evmap.sync.batch-size`, default 1000) via `BatchedIngestion`, not in one
transaction — a failed batch is retried per record so one bad row costs one station, and a run that lost
records finishes `PARTIAL` with a `failed` count. Never wrap `upsert()` in a single `@Transactional`
again: 113k+ BNetzA rows made that an all-or-nothing import. Adapters must not receive
`StationIngestionPort`; incremental sources take the narrow `SyncStateStore` and advance their watermark
only in `SourceAdapter.commitProgress()`. See ADR 0007.

Sources are ingested **one at a time**, each through its own `upsert()` call and wrapped in a
`SourceAdapterRun` that contains its failures: a source that breaks is truncated and recorded, the run
continues with the rest and finishes `PARTIAL` naming the source that failed. `commitProgress()` is
therefore decided per source — only one that delivered everything it fetched with zero ingestion
failures advances its watermark. Do not go back to one composed stream across adapters: it made the
least reliable government portal in Europe able to fail every other country's daily update. Downstream
(ingestion) exceptions must keep propagating — swallowing them would report a broken database as a
broken data source. See ADR 0013.

Source adapters live one per sub-package. `sync.bnetza` ingests the Bundesnetzagentur register from its
CSV bulk download, discovering the date-stamped URL from the Ladesäulenkarte page each run (the official
REST service requires a mail request; the ArcGIS route is token-gated now) — see ADR 0005. `sync.irve`
ingests the French consolidated register (Etalab/data.gouv.fr, Licence Ouverte, ~51k stations, no key);
its file has one row per charge point with non-adjacent rows per station, so it groups in memory rather
than streaming, and it is deliberately lenient about the publishers' data quality — see ADR 0012. A
charge point id is unique per source, but the file lists 20.742 of them under two or three stations: the
station whose own id the charge point id extends keeps it (else the first), the others keep the plug without
the EVSE-ID — never emit one id twice, it fails the second station in every run.
`sync.ch` ingests the Swiss register (BFE ich-tanke-strom / DIEMO, one gzipped OICP JSON, no key, source
token `DIEMO`, ~5k stations from ~14k EVSEs). The feed lists EVSEs, not stations, so the parser clusters
them by position within 35 m — deliberately more than the ingestion's 30 m match, so two of its stations
can never replace each other's charge points — and it reads only the static feed, never the live `status/`
one (ADR 0015).
`sync.es` ingests the Spanish register (MITERD, published by the DGT's National Access Point as one DATEX II v3
XML, CC-BY, no key, source token `MITERD`, ~10k stations from ~12k sites). The register has one site *per
operator*, so the parser bundles sites by position within 35 m across operators (`sync.support.PositionClusters`,
shared with `sync.ch`), for the same reason: the ingestion treats 30 m as "the same place" and replaces that station's charge points, so two sites
of one source inside it would overwrite each other on every run. The bundled station is named after the operator
with most charge points; every other operator stays on its own charge points (`master.charge_point.operator_name`,
ADR 0022). The EVSE-ID is the charge point's name, kept
only where it has the shape `ES*XXX*E…`. Austria has no adapter: the E-Control terms forbid storing and relaying the data (ADR 0012,
"Austria skipped"); nor has Italy, whose PUN register has no open export any more and whose portal API is not
open to foreign users (ADR 0012, "Italy skipped"). `sync.ocm` crawls Open Charge Map per country with keyset paging, throttled and page-capped because
their fair usage policy allows automated banning; it needs `OCM_API_KEY` and skips itself with a warning
without one — see ADR 0006, and fetches incrementally via `modifiedsince` with a weekly full refresh.
All normalize connector labels through `sync.ConnectorTypes` and service state through
`sync.AvailabilityStatus`, onto closed vocabularies the iOS `ConnectorType` / `AvailabilityStatus` enums
mirror; adding a value on either side without the other leaves it stored but unfilterable, or shown to
users as a raw token. `SourceAdapterRegistrationTests` asserts the full set of adapters and their source
tokens — extend it when adding a source, or a wiring mistake ships as a container that starts happily
and ingests one country less than it should.

**Which source owns a station is a per-country table, not order.** `evmap.sync.authority` in
`application-sync.yaml` maps a country to its authoritative source (`DE: BNetzA`, `FR: IRVE`, `CH: DIEMO`,
`LI: DIEMO`, `ES: MITERD`); `SourceAuthority` reads it and `PostgresStationIngestionRepository.mayUpdate` applies it. The
authority takes over a station another source created first; any other source is only linked once the
authority has claimed the station, and keeps maintaining the ones the authority has not. A country without
an entry has no authority and the last source to run wins — adapters have no defined order, so a new
national register must be added to the table, or Open Charge Map will overwrite its EVSE-IDs.
`SourceAdapterRegistrationTests` checks that every source named in the shipped table is a registered
adapter. See ADR 0012, "Switzerland (L2)".

**`availability` is the second data axis and is not part of `sync`.** Live occupancy is volatile,
per-EVSE and worthless after minutes, so it never enters `master.*` and never goes through
`StationIngestionPort`. It mirrors sync's shape — `AvailabilityProvider` is the interface (the
counterpart to `SourceAdapter`), providers live one per sub-package, nothing in `availability` names a
provider, and `AvailabilityProviderRegistrationTests` asserts the full set. The two packages do not
reference each other except for `sync.EvseIds`, deliberately shared so both sides of the join
normalize identically. Providers are queried **on demand by bounding box** and cached in-process for
one minute — do not turn this into a scheduled full poll: OCPDB's `last_updated` tracks the static
description, not the status, so an incremental refresh misses exactly the changes it exists to catch.
The in-process cache is a documented blocker to running a second API replica. See ADR 0015.
Providers: `availability.mobidata` (MobiData BW, OCPI by bounding box, DE/CH) and `availability.irve`
(France's national consolidation of `schema-irve-dynamique` — one country-wide CSV without coordinates,
so it answers every area with all of it, reused for one minute, rows older than 72 h dropped;
`AvailabilityService` keeps only the EVSE-IDs it asked for). TomTom was rejected (no EVSE-IDs); the
Mobilithek is blocked on registering an organisation.

**Live status attaches only on an exact EVSE-ID match.** There is no geographic, name-based or fuzzy
resolution anywhere in this feature, and adding one would be a regression, not a coverage win: it was
measured and produced real mispairings (a station at *Hedelfinger Str. 21* taking a live station at
*Nr. 25*), and operator names never match because the register names the legal entity while the feed
names the brand. Coverage is consequently partial — 14,5 % of live EVSEs resolved — and the rest is
honestly `UNKNOWN`. `master.charge_point` holds the EVSE-IDs; `charging_connector.charge_point_id` is
**nullable** because sources that report only totals (OCM) still hang connectors off the station.
Sources that describe charge points individually now emit one connector row per charge point, and
`StationService.aggregate` rebuilds the station totals on read, so the API payload is unchanged.

**`pricing` is the third axis: ad-hoc prices (ADR 0022).** Shaped like `availability` — `PriceProvider`
(`pricing.mobidata`: MobiData BW's OCPI tariffs, Germany), `PriceProviderRegistrationTests`, an in-process cache, an
exact EVSE-ID join, nothing written to `master.*` — plus the register prices `sync` stores in
`master.charge_point_price` (France: `sync.irve.IrvePriceText` reads the free-text `tarification` only where the
price is certain). `GET /api/v1/stations/{id}/charge-points` merges both, the live tariff first. **The product owner's
rule: a price shown wrongly is worse than none.** Every price is gross; an amount whose VAT basis is not established
is dropped, not guessed. OCPDB drops DATEX's `taxIncluded` (binary-butterfly/ocpdb#278), so a German tariff is shown
only when its net price × (1 + VAT) lands on whole cents, or its operator is in the dated table
`evmap.pricing.mobidata.vat-basis` (`VatBasisTable`, phase 5r): entered only on an exact match with the operator's
official ad-hoc price, amounts shown in whole cents, 19 % assumed for net operators of the rate-less chargecloud feed,
an entry the feed contradicts suspended at runtime (WARN). Runbook and evidence: `docs/operations/price-basis-operators.md`;
`ShippedVatBasisTableTests` binds the real file. An explicit `tax_included` will win once OCPDB delivers it. OCPDB also maps DATEX per-minute prices into
OCPI `TIME` unconverted, so the time unit is detected per feed from the median on every refresh and a change is
logged at WARN — never hard-code "per minute". Open Charge Map's `UsageCost` is deliberately not read.
`master.charge_point.operator_name` is the operator of a charge point where it differs from the station's (bundled
Spanish and Swiss sites); the ingestion stores it only then, so `NULL` means "the station's operator". Operator
filters and the directory (ADR 0014) match a station by any of its operators and hide it only when all are hidden.

Data separation: master/station data (sync-owned, read-only from the API) vs. user data (comments,
accounts — API-owned). Everything user-owned references **`user_data.account`** by its uuid; the
provider sign-ins hang off it in `provider_identity` (provider, subject, e-mail, `email_verified`).
Never reference a provider identity from other user data. See ADR 0018.

Auth (ADR 0018): Apple, Google and GitHub, integrated directly — no identity server. The iOS app signs
in with Apple natively (`POST /api/v1/auth/apple`, identity token checked by
`AppleIdentityTokenVerifier`); every other flow is an authorization-code exchange **in the backend**
(`CodeSignIn`: `GoogleSignIn`, `GitHubSignIn`, `AppleWebSignIn`) — clients get client id and redirect
URI from `GET /api/v1/auth/providers`, add `state` and PKCE, and post the code to
`/api/v1/auth/{provider}/code`. Client secrets never leave the backend; a provider with blank
credentials is simply off. Apple sign-ins (native and web) keep Apple's
refresh token **AES-GCM encrypted** (`TokenCipher`, `TOKEN_ENCRYPTION_KEY`) on the identity, because
`DELETE /api/v1/me` (`AccountDeletionService`) must revoke it at Apple before it deletes the account
in one cascading statement; `BearerTokenFilter` rejects tokens of deleted accounts (`KnownAccounts`).
Favorites (`api.favorite`) and station error reports (`api.stationreport`) are user data too, both
referencing a master station by uuid with `ON DELETE CASCADE`; a station report never edits `master.*`
(admins only resolve or dismiss it, and the next sync is the one writer) and its free text is never
logged. See ADR 0021.
Reports, blocks and the moderation queue live in `api.moderation` (reports never hide a comment for
others, blocks are anonymous rows, admins never see who reported); export and "my contributions" in
`api.account`. **A table added to `user_data` must join the export (`MyDataRepository`) and cascade from
`account`** — `MyDataTests.exportKnowsEveryUserDataTable` enforces the first. See ADR 0020. `AccountService` links a new identity to an existing account **only on a
matching verified e-mail, never an Apple relay address, and only at that identity's first sign-in**
— do not loosen any of the three, it is the account-takeover boundary. The API then issues its own
token (`AccessTokenService`, `sub` = account id), sent by iOS as a bearer header and by the web
client as the `HttpOnly` `evmap_session` cookie (`SessionCookie`), validated per request by
`BearerTokenFilter`. `SecurityConfiguration` is stateless (no sessions). CSRF is **on** for writes that
carry the session cookie and no `Authorization` header (`XSRF-TOKEN` cookie <-> `X-XSRF-TOKEN`, plain
handler for Angular); bearer, anonymous and the sign-in exchanges are exempt - do not widen the
exemption. See ADR 0018 (*Web session cookie and CSRF*). It
permits `/actuator/health`, `GET /api/v1/stations/**`, `GET /api/v1/operators`, the sign-in endpoints
under `/api/v1/auth/`; `/api/v1/admin/**` additionally requires the account's `is_admin` flag, read
from the database per request (`AdminAccounts`) and set **only by a manual `UPDATE`** — there is no
API that grants it. E-mail addresses are personal data: never log them (ADR 0002). Provider setup:
`docs/operations/sign-in-providers.md`.

`POST /api/v1/stations/along-route` (ADR 0017, phase 4) answers "stations in a corridor around this route" in
driving order. It is the one POST under `/stations/**` that is public (explicit `permitAll`; every other write
there needs a bearer token) and it is a read. The route is the request **body**, never a query string, and never
logged — not even at debug, only its point count (ADR 0002). The corridor test runs against `ST_Subdivide`d pieces
of the line, not the whole of it, or the GiST index would return a continent's bounding box; keep it that way
when touching `StationSpatialRepository.findAlongRoute`. Filter SQL is shared with the viewport query.

`GET /api/v1/operators` is the searchable charging-network directory the client's provider settings are
built from. There is no operator table and no operator id — `operator_name` is a string the adapters
normalize onto each station — so the directory is a `GROUP BY operator_name` and **the name is the
identity**, which is what the client keys its preferences by. Hidden networks arrive as repeated
`excludeOperator` params and are excluded *in the query*, never after it: the row limit is applied
server-side and ranked by power, so post-filtering would let hidden stations eat slots and silently
shrink the map. See ADR 0014.

Health: `/actuator/health/container` (group `container`, `include: db`) is what the Docker healthcheck
asks and must stay free of ingestion state — `IngestionHealthIndicator` contributes to the root
endpoint only, because the sync container waits on the API's health and a cold start would otherwise
deadlock. Every sync run is recorded in `master.sync_run` via `StationIngestionPort`. See ADR 0004.
A third URL, the `sync` group, adds `ingestionCompleteness`: a `PARTIAL` run reports the custom status
`INCOMPLETE`, which `application.yaml` ranks *below* UP for the root endpoint and maps to 503 only in
that group. Keep the root status order listing every status — one missing from it outranks all others.
See ADR 0019.

Backups and monitoring (ADR 0019): the `backup` service in `deploy/docker-compose.yml` runs
`deploy/backup/evmap-backup.sh` (image `ghcr.io/joinsider/evmap-backup`, released with the API image):
nightly `pg_dump` into restic on S3-compatible storage on another host, 7/4/3 rotation, a weekly
restore test into a scratch database, and push heartbeats to an Uptime Kuma that runs *outside* the
VPS. `deploy/backup/test/run.sh` rehearses the full cycle (CI job `backup`; on Apple Silicon run it
with `TEST_DB_IMAGE=kartoza/postgis:17-3.5`). The runbook is `docs/operations/backup-and-restore.md`.
When bumping the database's Postgres major version, bump the backup image's base with it.

Logging: SLF4J per class (`private static final Logger log = LoggerFactory.getLogger(X.class)`) — never a
shared/global logger instance, since the logger name drives level config and collector filtering. MDC keys
are centrally defined in `logging.LogContext`; use those constants rather than inventing field names.
`RequestLoggingFilter` owns the MDC lifetime and the `X-Request-Id` correlation id for HTTP requests, so
handlers must not clear the MDC. Output is stdout only: readable text locally, ECS JSON under the `docker`
profile (set inside both Dockerfiles). Never log tokens, secrets, comment bodies, or Apple provider
subjects — identify users by the internal `UserIdentity` UUID. See ADR 0002.

Schema: Liquibase-managed (`db/changelog/`), master/user-data tables are logically separated per the
Lastenheft even though currently in one schema — preserve that separation in any new migration.
**Only the API deployable migrates**: `application-sync.yaml` sets `spring.liquibase.enabled: false`,
and the sync container waits on the API's `/actuator/health` in Compose. Two Liquibase instances
bootstrapping the same empty database race on `CREATE TABLE databasechangelog` — do not re-enable it
for sync. See ADR 0003.

## Web architecture

`evmap_web/` is one Angular application (standalone components, zoneless, signals) with lazy feature
areas under `src/app/features/` (`login`, `account`, `admin`, `home`); the user web app of roadmap phase 8 joins
as more of them. `EvmapApi` (abstract class, `core/api/`) is the only way features reach the backend
— the counterpart to `ChargingStationRepository`; never inject `HttpClient` into a feature. The
session is an HttpOnly cookie the page cannot read: `AuthService` holds only the account and
restores it via `/me` on start, so reloads and language switches stay signed in. Only the PKCE
verifier and `state` of a running sign-in go to `sessionStorage`. The admin route guard only hides UI; the backend check is the boundary.
Every user-facing string is marked for `@angular/localize` (German source, `messages.en.xlf`), and a
missing translation fails the production build. The container's nginx serves `/de/` and `/en/`,
redirects everything else by `Accept-Language`, and proxies `/api/**` to the API on the same origin —
so there is no CORS configuration, and there must not be one.

## iOS architecture

`ChargingStationRepository` (protocol) is the only way UI/view models talk to the backend
(`RESTChargingStationRepository` is the current REST implementation). This exists specifically so the
networking layer can be swapped (GraphQL is an optional late roadmap item, no longer tied to v2) without touching `Features/*/Presentation`
code — never call networking APIs directly from a ViewModel or View.

Structure follows a feature-module layout under `Features/`, each split into `Domain` (models),
`Data` (repository implementations), and `Presentation` (SwiftUI views + view models): `Auth`, `Comments`,
`Map`, `Search`, `Settings`, `StationDetail` (with the ad-hoc price section, ADR 0022), `Stations`, `Favorites` (ADR 0021), `Routing` (ADR 0017), and `Account` (deletion,
export, contributions, blocks; ADR 0020). Shared networking primitives live in
`Core/Networking` (`APIClient`, `APIError`).

`Settings` owns everything that persists between launches. `AppSettings` is the stored value (one JSON
blob under a single `UserDefaults` key, via the `AppSettingsStoring` seam); `StationFilter` is the
criteria of one query, and is built **only** by `AppSettings.stationFilter` and `MapViewport
.effectiveFilter` — never assembled by hand. Keep that split: it is what lets `preferred`/`avoided` feed
route planning later without the station query knowing the provider vocabulary. `ProviderPreference`
declares all four cases but `selectableCases` gates the UI to the implemented two; stored values decode
leniently on purpose (unknown preference → `.shown`, unknown connector dropped, missing key → default),
because throwing sends the store down its corrupt-data path and resets *everything*. Settings persist on
change, but the map refetches only on sheet dismiss (`MapViewModel.apply(_:)`) — do not wire a filter
change straight to a fetch. Reset restores filters and provider preferences only, never the search
history or the sign-in. See ADR 0014.

`Favorites` is the device-or-account list (ADR 0021): `FavoriteList` holds whole stations, so it works signed
out and offline; `FavoritesViewModel` merges it with the account on sign-in (union) and clears the device
copy on sign-out (never at a signed-out launch), and undoes any change the backend refuses. It is owned by
`EVMapApp` like the settings, because the map draws its star badges from it.

`Search` (address autocomplete) deliberately does *not* go through `ChargingStationRepository` — it
talks to MapKit, not the backend — but has its own `AddressSearchProviding` seam for the same reason.
Recent searches are device-local and personal: never sent to the backend, and under ADR 0002's rules
only the *fact* of a search may be logged above `.debug`, never the address or its coordinate. The
search moves the camera and nothing else; `onMapCameraChange` then loads stations through the normal
viewport path, so there is no second fetch trigger. See ADR 0011.

`Routing` is the manual route planner (ADR 0017, phase 4). A route starts from an **info card** (`PlaceInfoCard`) that
opens after a search hit or a tap on a town, place of interest or station — there is no planner button in the toolbar,
and the station screen carries the same actions. `RoutePlannerViewModel` (owned by `EVMapApp`, like the settings and the
favorites) holds the plan as `RouteSlot` rows, which may still be empty ("route from here" has no destination yet), and
persists it on the device after every change (`FileRoutingStore`, JSON in Application Support — a route is too big for
`UserDefaults`), so a restart or a tunnel finds it again. Routes come only through `RouteProviding` (MapKit today; the
way out to Valhalla/turn-by-turn), points of interest through `NearbyPlacesProviding`. The simplified polyline
(`PolylineSimplifier`, ≤ 400 points) goes to the backend through `ChargingStationRepository.stationsAlongRoute`; detours
are exact for the ten stations nearest the road and estimated for the rest (`≈`). `MapViewModel` has two modes —
`.viewport` and `.route` — which must not overwrite each other: in route mode camera changes load nothing. Saved places,
saved routes and the open plan are device-only and never logged; "Mein Standort" is never written into a share link
(`RouteShareLink`, universal link `https://evmap.joinside.de/route?…`, the web container's `apple-app-site-association`
carries `applinks` for `/route`). Apple Maps takes only start → destination reliably, hence leg by leg
(`RouteHandoff`).
The map's sheets (info card, station screen, planner) are orchestrated by `MapPlaceFlow`, not by `MapScreen`:
SwiftUI cannot swap one sheet for another in a single step, so a choice made on a card is parked and carried out in
the card's `onDismiss`. Logic that does not need SwiftUI belongs in such testable types (`MapPlaceFlow`,
`MapCamera`, `RouteFraming`, `RouteStopRole`), because the view bodies and their tap closures are the part the unit
tests cannot reach; MapKit itself sits behind `DirectionsServing` and `NearbyPlacesProviding`.

## Constraints worth knowing before changing scope

- No Android client, no payment handling or charge-session control, no own turn-by-turn navigation —
  non-goals for v1 *and* v2 (Lastenheft §10, §11), not gaps to fill incidentally.
- Route planning (ADR 0017) and Google/GitHub sign-in plus an Angular web client (ADR 0018) *were*
  v1 non-goals and are now **v2 work** (Lastenheft §11); sign-in and the web skeleton landed in
  phase 1, account deletion, export and moderation in phase 2, the manual route planner (stage 1) in phase 4. They are built phase by phase in the
  order of `docs/roadmap.md`; do not start one incidentally or ahead of its phase.
- Real-time availability *was* on that list and is no longer: ADR 0015 reversed it and the Lastenheft
  was amended in the same change. What remains a non-goal is *complete* coverage — live status is
  shown only where a national access point supplies it and the EVSE-ID matches exactly.
- App strings must go through i18n resources (German base, English), never hardcoded — this is a stated
  requirement, not a style preference.
- Per-field provenance (`source` + `lastUpdated`) must be preserved through the merge logic; the authority
  source of a country wins ties for its locations (`evmap.sync.authority`, ADR 0012).

# TODOs for you as an AI Agent

If the user requests a new feature or new requirement then create a new ADR Document or edit an existing one within the docs/ folder inside the main project.
This ADR should be short but tell the user and later AI agent sessions why a feature was implemented and how it was implemented. If any open points are still open then ask the user for feedback on how to solve it but always provide options for the user to choose from.

## Roadmap

`docs/roadmap.md` is the source of truth for **what comes next and in which order** (v2, agreed with
the product owner). Its status table says which phase is done, in progress or open; the phase
sections and their ADRs say what each phase contains.

- To start or continue roadmap work, use the project skill **`/roadmap-phase`**
  (`.claude/skills/roadmap-phase/SKILL.md`). It holds the step-by-step procedure and the definition
  of done; do not improvise a different one.
- One phase per branch and PR. Finish the phase, update the roadmap status, then **stop and hand
  back** — never roll into the next phase unasked.
- The order and scope change only by the product owner's decision. When they decide something, record
  it in the roadmap (and the affected ADR) in the same change, instead of just acting on it.
- Ask open questions **one at a time** (AskUserQuestion), each with options and a recommended one.
  The product owner prefers this over a long list.
- Steps marked 👤 in the roadmap (developer-portal setup, OAuth apps, domains, device tests, the
  CarPlay entitlement request) only the product owner can do: name them early, don't block on them
  when the work can be prepared behind a seam or with test values.
