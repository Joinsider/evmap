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
than streaming, and it is deliberately lenient about the publishers' data quality — see ADR 0012.
`sync.ocm` crawls Open Charge Map per country with keyset paging, throttled and page-capped because
their fair usage policy allows automated banning; it needs `OCM_API_KEY` and skips itself with a warning
without one — see ADR 0006, and fetches incrementally via `modifiedsince` with a weekly full refresh.
All normalize connector labels through `sync.ConnectorTypes` and service state through
`sync.AvailabilityStatus`, onto closed vocabularies the iOS `ConnectorType` / `AvailabilityStatus` enums
mirror; adding a value on either side without the other leaves it stored but unfilterable, or shown to
users as a raw token. `SourceAdapterRegistrationTests` asserts the full set of adapters and their source
tokens — extend it when adding a source, or a wiring mistake ships as a container that starts happily
and ingests one country less than it should.

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
credentials is simply off. `AccountService` links a new identity to an existing account **only on a
matching verified e-mail, never an Apple relay address, and only at that identity's first sign-in**
— do not loosen any of the three, it is the account-takeover boundary. The API then issues its own
bearer token (`AccessTokenService`, `sub` = account id) validated per request by `BearerTokenFilter`.
`SecurityConfiguration` is stateless (no sessions, CSRF disabled since there's no cookie auth) and
permits `/actuator/health`, `GET /api/v1/stations/**`, `GET /api/v1/operators`, the sign-in endpoints
under `/api/v1/auth/`; `/api/v1/admin/**` additionally requires the account's `is_admin` flag, read
from the database per request (`AdminAccounts`) and set **only by a manual `UPDATE`** — there is no
API that grants it. E-mail addresses are personal data: never log them (ADR 0002). Provider setup:
`docs/operations/sign-in-providers.md`.

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
areas under `src/app/features/` (`login`, `admin`, `home`); the user web app of roadmap phase 8 joins
as more of them. `EvmapApi` (abstract class, `core/api/`) is the only way features reach the backend
— the counterpart to `ChargingStationRepository`; never inject `HttpClient` into a feature. The access
token lives in memory (`AuthService`) and is mirrored to `sessionStorage` so reloads and language
switches keep the sign-in (never `localStorage`); the PKCE verifier and `state` of a running sign-in go there too. The admin route guard only hides UI; the backend check is the boundary.
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
`Map`, `Search`, `Settings`, `StationDetail`, `Stations`. Shared networking primitives live in
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

`Search` (address autocomplete) deliberately does *not* go through `ChargingStationRepository` — it
talks to MapKit, not the backend — but has its own `AddressSearchProviding` seam for the same reason.
Recent searches are device-local and personal: never sent to the backend, and under ADR 0002's rules
only the *fact* of a search may be logged above `.debug`, never the address or its coordinate. The
search moves the camera and nothing else; `onMapCameraChange` then loads stations through the normal
viewport path, so there is no second fetch trigger. See ADR 0011.

## Constraints worth knowing before changing scope

- No Android client, no payment handling or charge-session control, no own turn-by-turn navigation —
  non-goals for v1 *and* v2 (Lastenheft §10, §11), not gaps to fill incidentally.
- Route planning (ADR 0017) and Google/GitHub sign-in plus an Angular web client (ADR 0018) *were*
  v1 non-goals and are now **v2 work** (Lastenheft §11); sign-in and the web skeleton landed in
  phase 1. They are built phase by phase in the
  order of `docs/roadmap.md`; do not start one incidentally or ahead of its phase.
- Real-time availability *was* on that list and is no longer: ADR 0015 reversed it and the Lastenheft
  was amended in the same change. What remains a non-goal is *complete* coverage — live status is
  shown only where a national access point supplies it and the EVSE-ID matches exactly.
- App strings must go through i18n resources (German base, English), never hardcoded — this is a stated
  requirement, not a style preference.
- Per-field provenance (`source` + `lastUpdated`) must be preserved through the merge logic; BNetzA wins
  ties for German locations.

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
