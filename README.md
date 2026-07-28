# EVMap backend

Spring Boot 4 backend for EVMap. It contains a stateless REST API and a separately deployable ingestion container that share PostgreSQL/PostGIS.

## Run locally

Set `JWT_SECRET` to a long random secret and optionally `APPLE_CLIENT_ID` (the iOS bundle/service client ID), then run:

```sh
cd evmap_service && ./mvnw package
cd .. && docker compose up --build
```

Copy `.env.example` to `.env` before running Compose, then replace the placeholder secrets.

The API exposes `GET /api/v1/stations` (latitude/longitude plus optional connector, power and operator filters), station details, public comment reads, Apple login, and authenticated comment create/update/delete. User identities live in `user_data`; sync-owned station data lives in `master`.

The sync container deliberately has no public port. Implement BNetzA and Open Charge Map adapters behind `sync.SourceAdapter`; they feed normalized `SourceStation` records through the sole `StationIngestionPort` write boundary.

For a minimal API-and-database deployment example, see `docker-compose.example.yml`. Replace its placeholder secrets before using it outside local development.

## iOS app

Open `evMap_ios/EVMap/EVMap.xcodeproj` in Xcode. The native SwiftUI app uses MapKit and talks to the REST API only through `ChargingStationRepository`, so the networking implementation can later be replaced with GraphQL without changing the UI.

For a device or an external backend, set the `API_BASE_URL` launch argument/user default to the HTTPS base URL of the deployed API (for example `https://evmap.example.com`). The development default is `http://127.0.0.1:8080`. Enable the **Sign in with Apple** capability for the `de.joinside.EVMap` App ID in the Apple Developer portal before signing a device build; the project already includes its entitlement.
