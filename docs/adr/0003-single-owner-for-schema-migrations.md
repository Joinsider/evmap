# 3. The API deployable owns schema migrations

- Status: Accepted
- Date: 2026-07-28
- Deciders: Johannes Popp

## Context

The API and the sync job are two deployables built from one Maven module, sharing one PostgreSQL
database. Liquibase is configured in the shared `application.yaml`, so **both** ran the migration on
startup, and `docker-compose.yml` gated both on nothing but `database: service_healthy` — so both
started at the same moment.

Against an empty database that is a race. Both instances issue `CREATE TABLE public.databasechangelog`
concurrently and the loser fails:

```
ERROR: duplicate key value violates unique constraint "pg_type_typname_nsp_index"
  Detail: Key (typname, typnamespace)=(databasechangelog, 2200) already exists.
```

Liquibase's own `databasechangeloglock` table cannot prevent this, because the lock table is one of
the tables being created — the lock only protects changesets, not the bootstrap of the changelog
infrastructure itself. Whichever container lost the race died with a `BeanCreationException`; which
one lost was down to scheduling. The bug was latent for as long as the compose volume already had a
schema, and surfaced on the first cold start after `down -v`.

## Decision

**The API deployable owns schema migration. The sync deployable never migrates.**

- `application-sync.yaml` sets `spring.liquibase.enabled: false`. With `ddl-auto: validate` (already
  the global default) the sync container fails loudly and immediately if it meets a schema that is
  missing or stale, rather than writing into a database it silently disagrees with.
- The API container gets a healthcheck on `/actuator/health`, and `sync` depends on
  `api: { condition: service_healthy }`. Because Liquibase runs during context refresh, the API is
  healthy only after the migration has completed — "API healthy" *is* "schema is ready".
- The healthcheck lives in `docker-compose.yml`, not as a Dockerfile `HEALTHCHECK`. Podman builds
  OCI-format images, which ignore the instruction (it warns at build time and is silently absent at
  run time). It runs the busybox `wget` already present in the Alpine base image, so no HTTP client
  has to be installed.

The API was chosen over the sync job because it is the always-on service: making the periodically
running batch job a startup precondition for the request-serving deployable inverts the dependency
that actually matters.

## Consequences

### Positive

- Cold start works: verified against a wiped volume — Liquibase runs exactly once, in the API, and
  the sync container starts afterwards with no warnings or errors.
- Migration ownership is explicit rather than incidental. Two processes writing DDL to one database
  was never intended, only unconfigured.
- A schema/code mismatch in the sync container now fails at startup with a Hibernate validation
  error instead of at the first write.
- While touching `Dockerfile.sync`: it ran as root, unlike the API image. It now uses the same
  unprivileged `spring` user (verified: `uid=999(spring)` in the running container).

### Negative / accepted risks

- **The sync deployable can no longer bootstrap a database on its own.** Running it against an empty
  database fails by design. This is the intended trade, but it is a new operational precondition:
  any environment that runs sync must run the API's migration first.
- **`depends_on` ordering does not survive outside Compose.** In Kubernetes or ECS both would start
  concurrently again; the sync container would crash-loop until the API's migration lands. That is a
  safe failure (it cannot corrupt anything, and it recovers on its own), but it is a restart storm,
  not graceful waiting. A future init-container or migration job is the proper fix there.
- **`curl` in the API image** for the healthcheck: a small amount of extra surface for an
  operational need. (No longer true since [ADR 0005](0005-alpine-based-container-images.md) — the
  check uses the busybox `wget` of the Alpine base image and nothing is installed.)
- **The Lastenheft §6 split into separate services will have to revisit this.** Once the two are
  genuinely independent deployables, "the API happens to migrate" stops being a good answer; a
  dedicated migration step is the natural successor.

## Alternatives considered

**Sync owns migrations** (it owns master data, so the schema is arguably its concern). Rejected: it
makes a 24-hour batch container a startup dependency of the request-serving API.

**Keep Liquibase in both, only serialize the startup order in Compose.** The smallest change, and
each deployable could still bootstrap a database alone — but the race is only hidden, not removed,
and returns in any environment that starts both concurrently.

**A separate migration job/container that both wait on.** The cleanest answer and the right one once
the services split for real (Lastenheft §6). Today it adds a third deployable to a stack whose two
existing ones are built from the same jar.

## References

- `evmap_service/src/main/resources/application-sync.yaml` — `liquibase.enabled: false`
- `docker-compose.yml` — API healthcheck and the `service_healthy` gate
- `evmap_service/Dockerfile` — the runtime image behind the healthcheck
- [ADR 0005](0005-alpine-based-container-images.md) — Alpine base images; replaces `curl` with the
  base image's busybox `wget`
- [ADR 0002](0002-global-logging-for-the-backend-service.md) — the structured logging that made the
  failure legible in the first place
