# 2. Global logging for the backend service

- Status: Accepted
- Date: 2026-07-28
- Deciders: Joinsider

## Context

The service had no logging of its own. Everything visible in `docker compose logs` came from
Spring Boot, Hibernate and Hikari during startup; after the context was ready, both deployables
went silent. That silence is the problem in three concrete situations:

- **A request fails and nobody knows why.** An expired bearer token was swallowed by
  `BearerTokenFilter`'s empty `catch`, so the caller saw a 401/403 with no server-side trace of
  the rejection. A malformed Apple identity token surfaced as a 500 with nothing naming Apple.
- **The sync deployable is a black box.** It has no HTTP port, runs on a 24 h fixed delay, and
  wrote master data with no record of what it touched. A run that ingested nothing and a run that
  never started look identical from outside the container.
- **There is nothing to correlate on.** With one iOS client, one API and one sync job writing to
  the same database, a report of "the app showed no stations near me" cannot be tied to a specific
  request without a per-request identifier.

The two deployables also have different consumers for their output: locally a developer reads the
console, in Docker the output is `docker logs` and eventually a log collector. Those want the same
events in different shapes.

## Decision

### 1. SLF4J per class, one shared diagnostic-context vocabulary

No custom logging facade or singleton "global logger" class. Every class that logs declares its own
`private static final Logger log = LoggerFactory.getLogger(X.class)` — the logger name is then the
class name, which is what makes `logging.level.<package>` tuning work and what an ECS collector
indexes on.

What *is* global is the **context**: `de.joinside.evmap_service.logging.LogContext` defines the MDC
keys (`requestId`, `userId`, `httpMethod`, `httpPath`, `httpStatus`, `durationMs`, `job`, `source`)
used everywhere, so no two call sites invent competing names for the same field. Those keys appear
as first-class JSON fields in structured output and in the console pattern locally.

The `logging` package sits beside `api` and `sync` rather than inside either. It is infrastructure
both deployables depend on, and it references nothing from either package, so the write-boundary
separation the Lastenheft requires is untouched.

### 2. One appender, two formats, selected by Spring profile

Both deployables log to stdout only — never to a file. Container orchestration owns log collection;
a file inside a container is a log that gets lost on restart.

- **Local (no profile):** Spring Boot's default human-readable console pattern, extended via
  `logging.pattern.correlation` so every line carries `[requestId]`.
- **Docker (`docker` profile):** `logging.structured.format.console: ecs` — Spring Boot's built-in
  structured logging, emitting one ECS JSON object per line. No `logback-spring.xml` and no
  Logstash encoder dependency is needed.

The profile is set in the images themselves (`ENV SPRING_PROFILES_ACTIVE=docker` in `Dockerfile`,
`ENV SPRING_PROFILES_ACTIVE=sync,docker` in `Dockerfile.sync`) rather than in `docker-compose.yml`,
so any way of running the image gets container-appropriate output. `LOG_FORMAT`, `LOG_LEVEL_APP`
and `LOG_LEVEL_ROOT` remain env-tunable; `LOG_FORMAT=` (empty) restores plain text inside a
container for a debugging session.

### 3. `RequestLoggingFilter` as the correlation and access-log point

A `OncePerRequestFilter` ordered ahead of the Spring Security chain
(`SecurityFilterProperties.DEFAULT_FILTER_ORDER - 10`) so requests rejected by security are logged
too. It:

- accepts an inbound `X-Request-Id`, or generates a 16-character id, and echoes it on the response
  so a client bug report can name the exact request;
- owns the MDC lifetime for the request — it populates the context and clears it in `finally`,
  which matters on pooled request threads;
- emits one completion line per request with method, path, status and duration, at INFO for < 400,
  WARN for 4xx and ERROR for 5xx or a propagating exception;
- skips `/actuator/health`, which would otherwise dominate the log at container-healthcheck rate.

`BearerTokenFilter` adds `userId` to that context after authenticating, so every later line of the
request is attributable without threading the user through call signatures.

### 4. Instrumented call sites

Chosen so that each externally visible outcome has exactly one authoritative log line, at the level
that matches its operational meaning:

| Where | Level | Event |
| --- | --- | --- |
| `RequestLoggingFilter` | INFO/WARN/ERROR | every request's outcome and duration |
| `BearerTokenFilter` | WARN | rejected bearer token (reason only, never the token) |
| `AppleIdentityTokenVerifier` | INFO / WARN | verification configuration at startup; rejected Apple token |
| `AuthController` / `UserIdentityService` | INFO | access token issued; new user identity created |
| `StationService` | WARN / DEBUG | rejected radius, unknown station id, slow spatial query (≥ 1 s); query parameters and result counts |
| `CommentService` | INFO / WARN | comment created/updated/deleted; validation failure; write attempt on a comment the caller does not own |
| `SyncJob` | INFO / ERROR | run start, run summary with counts and duration, run failure |
| `PostgresStationIngestionRepository` | INFO / DEBUG / ERROR | progress every 1000 records; per-station create/update decisions; the record that broke a run |
| `StartupLogger` | INFO | one line per boot: profiles, log format, datasource, sync enabled |

To make the sync summary possible, `StationIngestionPort.upsert` now returns
`IngestionResult(processed, created, updated, unchanged)` instead of `void`. The counting happens at
the write boundary, but the decision of how to report a run stays in `SyncJob`.

`SyncJob` now catches and logs a failing run instead of letting it propagate, so an upstream outage
cannot silently affect the scheduler's future runs.

### 5. What is deliberately never logged

Access tokens, Apple identity tokens, the JWT secret, comment bodies, and the Apple provider
subject. Users are identified by the internal `UserIdentity` UUID, which is exactly the indirection
`UserIdentityService` exists to provide. The startup line strips any query string from the JDBC URL.

## Consequences

### Positive

- Every HTTP request produces one greppable outcome line with a correlation id that a client can be
  asked to quote.
- The sync container reports what it did; a run that ingests nothing is now distinguishable from a
  run that never happened.
- `docker logs` output is machine-parseable without a log-shipper-side grok pattern, while local
  development keeps readable text.
- Authentication failures, ownership violations and slow spatial queries are visible without a
  debugger attached.

### Negative / accepted risks

- **One INFO line per request.** At meaningful traffic this is the dominant log volume. It is the
  point of an access log, and `LOG_LEVEL_ROOT`/`LOG_LEVEL_APP` allow raising the floor, but the cost
  is real and grows linearly with traffic.
- **The `docker` profile is now load-bearing.** An image started without it silently falls back to
  plain text. `StartupLogger` prints the effective format precisely so this is one line away from
  being noticed.
- **`StationIngestionPort` changed shape.** A published interface with one implementation today, so
  the cost is nil now and rises once other implementations exist.
- **Ingestion progress logging assumes a countable stream.** `IngestionResult` counts are only
  meaningful because `upsert` consumes the stream eagerly inside one transaction. A future batched
  or parallel ingestion has to revisit the counters.
- **A swallowed sync failure is only visible in the log.** There is no metric, alert, or health
  indicator for "the last sync run failed" — a monitoring gap this ADR does not close.

### Neutral

- MDC values live on the request thread. Any future `@Async` or reactive work will not inherit the
  correlation id without explicit propagation.

## Open points — both resolved on 2026-07-28

1. **A malformed Apple identity token produced HTTP 500.** Resolved: `ApiExceptionHandler` maps
   `BadJwtException` to **401 Unauthorized** with `{"error": "Invalid identity token"}`. The split is
   deliberate — `BadJwtException` means the credential is bad (malformed, expired, wrong
   issuer/audience) and is the client's problem, while a plain `JwtException` means Apple's JWKS
   endpoint failed us and must stay a 5xx. Mapping both would have reported our own outage as the
   caller's mistake.
2. **There was no alerting on a failed sync run.** Resolved in
   [ADR 0004](0004-ingestion-run-history-and-health-reporting.md): the job records every run in
   `master.sync_run` and the API reports on it via `/actuator/health`.

## Alternatives considered

**A custom `GlobalLogger` singleton or a static wrapper around SLF4J.** Rejected: it collapses every
logger name to one, breaking per-package level configuration and log-collector filtering, and it
buys nothing SLF4J does not already provide.

**`logback-spring.xml` with a `<springProfile>` switch and the Logstash encoder.** The conventional
pre-3.4 answer, and it works — but it means an extra dependency and an XML file that has to be kept
in sync with Boot's own defaults. Boot's native structured logging covers the same ground in three
lines of YAML.

**Logging to a file with rotation in addition to stdout.** Rejected for containers: a file inside a
container is lost on restart and invisible to `docker logs`. If a file is ever needed, it belongs to
the platform, not the application.

**Filter-based request/response *body* logging.** Rejected: it requires wrapping the request and
response streams, and comment bodies and identity tokens are exactly the payloads that must not be
logged.

**Micrometer tracing (`spring-boot-starter-actuator` + `micrometer-tracing`) instead of a hand-rolled
correlation id.** The right answer once there is more than one service to trace across. Today it
would add a dependency and a sampling decision to solve a problem one filter solves, and
`logging.pattern.correlation` is the same hook tracing would use — so adopting it later does not
invalidate the call sites instrumented here.

## References

- `evmap_service/src/main/java/de/joinside/evmap_service/logging/LogContext.java`
- `evmap_service/src/main/java/de/joinside/evmap_service/logging/RequestLoggingFilter.java`
- `evmap_service/src/main/java/de/joinside/evmap_service/logging/StartupLogger.java`
- `evmap_service/src/main/resources/application.yaml` — local console pattern and levels
- `evmap_service/src/main/resources/application-docker.yaml` — ECS JSON output
- `evmap_service/src/main/java/de/joinside/evmap_service/sync/StationIngestionPort.java` — `IngestionResult`
