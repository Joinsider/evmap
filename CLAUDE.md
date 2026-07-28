# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

EVMap is an EV charging station app for Europe: a native iOS/SwiftUI client (`evMap_ios/`) backed by a
Spring Boot 4 service (`evmap_service/`). Data is merged from the Bundesnetzagentur Ladesäulenregister
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

Full local stack (Postgres + API + sync container):

```sh
cd evmap_service && ./mvnw package
cd .. && docker compose up --build
```

Copy `.env.example` to `.env` first and set `JWT_SECRET` (long random secret); `APPLE_CLIENT_ID` is optional
for local dev. The sync container has no public port and runs on the `sync` Spring profile
(`application-sync.yaml`), triggering ingestion on a fixed 24h delay.

iOS (`evMap_ios/EVMap/EVMap.xcodeproj`): open and build in Xcode. To point the app at a non-default
backend, set the `API_BASE_URL` launch argument/user default (dev default is `http://127.0.0.1:8080`).
Sign in with Apple requires the capability enabled for `de.joinside.EVMap` in the Apple Developer portal.

## Backend architecture

Two deployables share one PostgreSQL/PostGIS database but are built from the same Maven module via
separate Dockerfiles (`Dockerfile` for the API, `Dockerfile.sync` for the ingestion job) — this is
intentional prep for later splitting into fully separate services (see Lastenheft §6) without a code
rewrite. Do not blur this boundary:

- **`api` package** — stateless REST API (`station`, `comment`, `auth`, `security` sub-packages). Only
  reads master data, only writes user data.
- **`sync` package** — ingestion job. `SourceAdapter` is the interface every external data source (BNetzA,
  OCM) must implement, normalizing into `SourceStation`. `StationIngestionPort` is the _sole_ write
  boundary into master data (`PostgresStationIngestionRepository` implements it). There must be no direct
  reference from `sync` into `api` repositories/entities, or vice versa.

Ingestion commits in batches (`evmap.sync.batch-size`, default 1000) via `BatchedIngestion`, not in one
transaction — a failed batch is retried per record so one bad row costs one station, and a run that lost
records finishes `PARTIAL` with a `failed` count. Never wrap `upsert()` in a single `@Transactional`
again: 113k+ BNetzA rows made that an all-or-nothing import. Adapters must not receive
`StationIngestionPort`; incremental sources take the narrow `SyncStateStore` and advance their watermark
only in `SourceAdapter.commitProgress()`, which `SyncJob` calls solely on a zero-failure run. See ADR 0007.

Source adapters live one per sub-package: `sync.bnetza` ingests the Bundesnetzagentur register from its
CSV bulk download, discovering the date-stamped URL from the Ladesäulenkarte page each run (the official
REST service requires a mail request; the ArcGIS route is token-gated now) — see ADR 0005. `sync.ocm`
crawls Open Charge Map per country with keyset paging, throttled and page-capped because their fair usage
policy allows automated banning; it needs `OCM_API_KEY` and skips itself with a warning without one — see
ADR 0006, and fetches incrementally via `modifiedsince` with a weekly full refresh. Both normalize
connector labels through `sync.ConnectorTypes` and service state through `sync.AvailabilityStatus`, onto
closed vocabularies the iOS `ConnectorType` / `AvailabilityStatus` enums mirror; adding a value on either
side without the other leaves it stored but unfilterable, or shown to users as a raw token.

Data separation: master/station data (sync-owned, read-only from the API) vs. user data (comments, user
identities — API-owned). `UserIdentity`/`UserIdentityService` is deliberately a thin internal ID layer so
additional auth providers can be added later without touching how the rest of the app references users.

Auth: Sign in with Apple only. The API verifies the client's Apple identity token against Apple's JWKS
endpoint (`AppleIdentityTokenVerifier`), then issues its own bearer access token (`AccessTokenService`)
validated per-request by `BearerTokenFilter`. `SecurityConfiguration` is stateless (no sessions, CSRF
disabled since there's no cookie auth) and permits `/actuator/health`, `GET /api/v1/stations/**`, and
`/api/v1/auth/apple` without auth; everything else requires a bearer token.

Health: `/actuator/health/container` (group `container`, `include: db`) is what the Docker healthcheck
asks and must stay free of ingestion state — `IngestionHealthIndicator` contributes to the root
endpoint only, because the sync container waits on the API's health and a cold start would otherwise
deadlock. Every sync run is recorded in `master.sync_run` via `StationIngestionPort`. See ADR 0004.

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

## iOS architecture

`ChargingStationRepository` (protocol) is the only way UI/view models talk to the backend
(`RESTChargingStationRepository` is the current REST implementation). This exists specifically so the
networking layer can be swapped for GraphQL later (planned v2) without touching `Features/*/Presentation`
code — never call networking APIs directly from a ViewModel or View.

Structure follows a feature-module layout under `Features/`, each split into `Domain` (models),
`Data` (repository implementations), and `Presentation` (SwiftUI views + view models): `Auth`, `Comments`,
`Map`, `StationDetail`, `Stations`. Shared networking primitives live in `Core/Networking`
(`APIClient`, `APIError`).

## Constraints worth knowing before changing scope

- No Android client, no route planning, no real-time availability, no payment handling, no external
  identity provider beyond Apple — these are explicit v1 non-goals (Lastenheft §10), not gaps to fill
  incidentally.
- App strings must go through i18n resources (German base, English), never hardcoded — this is a stated
  requirement, not a style preference.
- Per-field provenance (`source` + `lastUpdated`) must be preserved through the merge logic; BNetzA wins
  ties for German locations.

# TODOs for you as an AI Agent

If the user requests a new feature or new requirement then create a new ADR Document or edit an existing one within the docs/ folder inside the main project.
This ADR should be short but tell the user and later AI agent sessions why a feature was implemented and how it was implemented. If any open points are still open then ask the user for feedback on how to solve it but always provide options for the user to choose from.
