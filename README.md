# EVMap backend

Spring Boot 4 backend for EVMap. It contains a stateless REST API and a separately deployable ingestion container that share PostgreSQL/PostGIS.

## Run locally

Set `JWT_SECRET` to a long random secret and optionally `APPLE_CLIENT_ID` (the iOS bundle/service client ID), then run:

```sh
cd evmap_service && ./mvnw package
cd .. && docker compose up --build
```

Copy `.env.example` to `.env` before running Compose, then replace the placeholder secrets.

The API exposes `GET /api/v1/stations` (latitude/longitude plus optional power and operator filters, and a repeatable `connectorType` parameter matched case-insensitively), station details, public comment reads, Apple login, and authenticated comment create/update/delete. User identities live in `user_data`; sync-owned station data lives in `master`.

Two health URLs, with different meanings: `/actuator/health/container` answers "can this instance serve
requests" and is what the container healthcheck uses, while `/actuator/health` also covers ingestion and
reports DOWN if the last sync run failed or the newest successful one is older than `SYNC_MAX_AGE`
(default 48 h). Point an uptime monitor at the former, an operational alert at the latter
([ADR 0004](docs/adr/0004-ingestion-run-history-and-health-reporting.md)). Every run is recorded in
`master.sync_run`.

The API container owns the Liquibase migration and the sync container waits for it to report healthy;
running sync against a database that has never seen the API fails on purpose ([ADR 0003](docs/adr/0003-single-owner-for-schema-migrations.md)).

The sync container deliberately has no public port. Two adapters sit behind `sync.SourceAdapter` and feed
normalized `SourceStation` records through the sole `StationIngestionPort` write boundary:

- **Bundesnetzagentur** (`sync.bnetza`) — the published CSV bulk download, CC BY 4.0. No credentials
  needed; the date-stamped URL is discovered from the Ladesäulenkarte page each run. Pin an edition or
  point at a local copy with `BNETZA_CSV_URL`. ([ADR 0005](docs/adr/0005-bundesnetzagentur-source-adapter.md))
- **Open Charge Map** (`sync.ocm`) — **needs an API key.** Sign in at
  [openchargemap.org](https://openchargemap.org), then *my profile → my apps → Register An Application*,
  and set `OCM_API_KEY` in your `.env`. Without it the adapter logs a warning and skips itself, so the
  stack still runs on German data alone. `OCM_COUNTRY_CODES` bounds the crawl (default `DE,AT,CH,NL,BE,LU,FR,IT,DK,PL,CZ`);
  widen it deliberately — OCM's fair usage policy permits banning callers that query indiscriminately.
  ([ADR 0006](docs/adr/0006-open-charge-map-source-adapter.md))

The first sync run starts right after the container comes up and then repeats every `evmap.sync.fixed-delay`
(24h) — no separate import step is needed. Expect the initial BNetzA import to be ~113k stations; it commits
in batches of `SYNC_BATCH_SIZE` (1000), so an interrupted run keeps what it already stored and the next run
completes it ([ADR 0007](docs/adr/0007-batched-ingestion-transactions.md)). A run that could not ingest some
records finishes `PARTIAL`; `/actuator/health` reports the `failed` count.

## Logging

Both deployables log to stdout only. Locally you get Spring Boot's readable console format with the
request correlation id in brackets; the Docker images activate the `docker` profile and emit one ECS
JSON object per line instead, so `docker logs` output can be shipped without parsing (see
[ADR 0002](docs/adr/0002-global-logging-for-the-backend-service.md)).

Every HTTP request produces one access-log line with method, path, status and duration, and carries an
`X-Request-Id` — taken from the request header if the client sends one, generated otherwise, and always
echoed on the response. Quote that id when reporting a backend problem.

Tune via environment (see `.env.example`): `LOG_LEVEL_APP` for `de.joinside.evmap_service`,
`LOG_LEVEL_ROOT` for everything else, and `LOG_FORMAT` for the container output format — `ecs`
(default), `gelf`, `logstash`, or empty for plain text.

For a minimal API-and-database deployment example, see `docker-compose.example.yml`. Replace its placeholder secrets before using it outside local development.

## iOS app

Open `evMap_ios/EVMap/EVMap.xcodeproj` in Xcode. The native SwiftUI app uses MapKit and talks to the REST API only through `ChargingStationRepository`, so the networking implementation can later be replaced with GraphQL without changing the UI.

The app picks its backend from the target environment: simulator builds default to `http://127.0.0.1:8080`, everything else to `https://evmap.joinside.de` (see `Core/Networking/APIEnvironment.swift` and [ADR 0001](docs/adr/0001-client-environment-selection-and-multi-value-station-filters.md)). To override either — for example to point a device build at a development server on the LAN — set the `API_BASE_URL` launch argument/user default to the base URL you want. Enable the **Sign in with Apple** capability for the `de.joinside.EVMap` App ID in the Apple Developer portal before signing a device build; the project already includes its entitlement.
