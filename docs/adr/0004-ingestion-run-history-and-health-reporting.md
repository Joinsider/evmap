# 4. Ingestion run history and health reporting

- Status: Accepted
- Date: 2026-07-28
- Deciders: Johannes Popp

## Context

[ADR 0002](0002-global-logging-for-the-backend-service.md) left one gap open: `SyncJob` catches a
failed run and logs it at ERROR so the scheduler keeps running, but nothing outside the log stream
knows it happened. A log line is only an alert if something is watching the log, and nothing is.

The obvious answer — an Actuator health indicator in the sync deployable — is not available. The
sync container runs with `spring.main.web-application-type: none` and has no public port by design
(Lastenheft §6); it can expose no HTTP endpoint at all. Whatever reports on ingestion has to be the
API, which means the state has to reach the API through the only thing the two share: the database.

There is also a trap in the obvious wiring. [ADR 0003](0003-single-owner-for-schema-migrations.md)
made the sync container wait for the API's healthcheck. If a failed or never-run ingestion made the
API unhealthy, a cold start would deadlock permanently: sync waits for the API to be healthy, the
API is unhealthy because sync has never run.

## Decision

### 1. The sync job records every run in `master.sync_run`

A new Liquibase changeset (`002-sync-run-history.sql`) adds `master.sync_run` — start, finish,
status (`RUNNING`/`SUCCEEDED`/`FAILED`/`SKIPPED`), the four ingestion counters, and a truncated error
message. It lives in `master` because it is sync-owned operational data, and it is written through
`StationIngestionPort` (`startRun`, `finishRun`) rather than a second repository, so the port remains
the **sole** write boundary into sync-owned data.

Both bookkeeping writes use `Propagation.REQUIRES_NEW`. Without that, the `finishRun` marking a run
as FAILED would be part of the very transaction that is rolling back, and every failure would erase
its own record.

`SKIPPED` exists to distinguish "the job ran and had nothing to do" from "the job never ran" — today
that is the normal outcome, since no `SourceAdapter` is implemented yet.

### 2. The API reports on it, but not in the group the container healthcheck uses

`IngestionHealthIndicator` (bean name `ingestion`) reads the run history read-only — the same
relationship the API already has to the rest of `master` — and contributes to `/actuator/health`:

- last completed run `FAILED` → **DOWN**
- a successful run exists, but the newest is older than `evmap.sync.max-age` (default 48 h, twice the
  fixed delay so one missed run is not an alert) → **DOWN**
- no runs, or only `SKIPPED` runs → **UP**. Until an adapter exists, every run is skipped by design;
  reporting DOWN for a system behaving exactly as specified would train everyone to ignore it.

The container healthcheck in `docker-compose.yml` asks for the **`container` health group**
(`include: db`), not the root endpoint. That group answers "can this instance serve requests", which
is what the sync container's `depends_on` gate needs, and it structurally cannot be affected by
ingestion state. The deadlock described above is therefore not merely avoided by tuning — it cannot
be expressed.

`SecurityConfiguration` now permits `/actuator/health/**` rather than the exact path, so the group
endpoints are reachable. Health details stay hidden (Boot's default `show-details: never`), so this
exposes status only — error messages live in `master.sync_run` and the log, not on a public endpoint.

### 3. The sync image runs unprivileged

`Dockerfile.sync` was still running as root while the API image had a `spring` user. Aligned: same
`groupadd`/`useradd`, `--chown` on the jar, `USER spring`.

## Consequences

### Positive

- A failed ingestion is visible over HTTP, survives container restarts, and carries its counters and
  error message.
- Verified end to end: a `FAILED` row makes `/actuator/health` return **503 DOWN** while
  `/actuator/health/container` stays **200 UP** and the API container stays `healthy`.
- `SKIPPED` makes the adapter-less state legible instead of indistinguishable from a dead job.
- Both containers now run as the same unprivileged user.

### Negative / accepted risks

- **The root `/actuator/health` now fails on an ingestion problem.** Any uptime monitor pointed at
  it will report the API as down when only the data is stale. That is the intended signal, but it
  means the monitor's URL choice now carries meaning: `/actuator/health/container` for "is the
  service up", the root for "is the system healthy".
- **`sync_run` grows without bound.** One row per run is ~365 rows a year at the current 24 h delay
  — irrelevant for a long time, and deliberately not solved with a retention job yet.
- **A run that dies with the container stays `RUNNING` forever.** `finishRun` never executes on a
  hard kill, so the row is orphaned. The indicator ignores unfinished runs, so this shows up as
  staleness after `max-age` rather than as a distinct "crashed" state.
- **The API depends on a sync-owned table at startup config time.** It is read-only and defensive
  (a failing read logs a warning and reports UP rather than throwing), but it is one more place
  where the two deployables are not yet as separate as Lastenheft §6 intends.
- **`Propagation.REQUIRES_NEW` costs a second connection** from the pool during ingestion. With a
  single-threaded scheduled job this is free; a future parallel ingestion should re-check it.

## Alternatives considered

**Health indicator derived from data freshness** (`max(last_updated_at)` in `master.station_source`),
no new table. Fewer moving parts, but it cannot tell "the run failed" from "no adapter is
implemented" or "the source had nothing new" — three states that need three different reactions.

**Micrometer counter / metrics endpoint.** The right shape for trend data, but the sync container has
no HTTP endpoint to scrape, so it would need a push gateway — new infrastructure for a stack that
currently has three containers.

**Log-based alerting only.** Zero code, and the structured `job=station-sync` ERROR line is already
there for it. Rejected as the *only* mechanism because it makes the alert depend on a log collector
that does not exist yet; the ERROR line remains as a second signal for when one does.

**Writing run history through a separate repository** instead of `StationIngestionPort`. Rejected: it
would create a second write path into sync-owned data, which is exactly the boundary the port exists
to keep singular.

## References

- `evmap_service/src/main/resources/db/changelog/002-sync-run-history.sql`
- `evmap_service/src/main/java/de/joinside/evmap_service/sync/StationIngestionPort.java`
- `evmap_service/src/main/java/de/joinside/evmap_service/api/health/IngestionHealthIndicator.java`
- `evmap_service/src/main/resources/application.yaml` — the `container` health group
- `docker-compose.yml` — healthcheck pointing at that group
