# EVMap backend

Spring Boot 4 backend for EVMap. It contains a stateless REST API and a separately deployable ingestion container that share PostgreSQL/PostGIS.

## Run locally

Set `JWT_SECRET` to a long random secret and optionally `APPLE_CLIENT_ID` (the iOS bundle/service client ID), then run:

```sh
cd evmap_service && ./mvnw package
cd .. && docker compose up --build
```

The API exposes `GET /api/v1/stations` (latitude/longitude plus optional connector, power and operator filters), station details, public comment reads, Apple login, and authenticated comment create/update/delete. User identities live in `user_data`; sync-owned station data lives in `master`.

The sync container deliberately has no public port. Implement BNetzA and Open Charge Map adapters behind `sync.SourceAdapter`; they feed normalized `SourceStation` records through the sole `StationIngestionPort` write boundary.
