# 13. Per-source isolation in the sync run

- Status: Accepted
- Date: 2026-07-29
- Deciders: Joinsider

## Context

The sync package was written for two sources and worked well for two. Adding France (ADR 0012) made the
third, and the plan there names four more national registers behind it. Three properties that were
harmless at two sources become defects somewhere between three and seven.

**One composed stream meant one shared fate.** `SyncJob` built the run as

```java
adapters.stream().flatMap(SourceAdapter::fetchStations)
```

and handed that to `ingestion.upsert(...)`. An exception anywhere in it — a national portal that 404s,
a page layout that stopped parsing, a download that dies mid-file — ended the whole ingestion and the
run finished `FAILED`. With BNetzA and OCM that is a bad day. With BNetzA, OCM, France, Switzerland,
Austria, the Netherlands and Poland it means the least reliable government portal in Europe decides
whether the other six countries get updated today.

**Progress was gated globally.** `commitProgress()` ran only when the entire run had zero failed
records, across all sources. ADR 0006 recorded this as an open point: one bad record anywhere held
back every incremental source's watermark. With more sources the odds of a clean run fall, and OCM's
incremental fetching quietly stops advancing.

**Reporting was per run, not per source.** The run reported totals. "Which country contributed
nothing today" was not answerable without reading log lines, and `master.sync_run` recorded no hint of
which source broke.

There was also a latent bug worth naming: each adapter opened `LogContext.scope(SOURCE, …)` inside its
own `fetchStations()`. That scope closes when `fetchStations()` *returns* — which, for a lazy adapter,
is before a single record has been produced. The `source` tag was therefore absent from exactly the
log lines that were about crawling and mapping.

## Decision

**Ingest one source at a time, contain each source's failures, and decide everything per source.**

### `SourceAdapter` declares what it is

Two methods were added to the contract:

- `String source()` — the token the adapter writes into every `SourceStation` it emits. The run tags
  its logs with it, the ingestion attributes records by it, `SyncStateStore` scopes watermarks by it.
  Previously it existed only as a constant buried in each parser, which is why nothing outside an
  adapter could name the source it was talking about.
- `boolean enabled()` — the operator-facing on/off switch, checked centrally. Each adapter used to
  test its own flag inside `fetchStations()` and log its own skip message. One place decides now, and
  a disabled source is reported once in a consistent form.

`enabled()` is deliberately *not* the place for "cannot run today". A missing OCM API key still
returns an empty stream from `fetchStations()` with a warning naming the property, exactly as ADR 0006
specified: a deployment running BNetzA only is legitimate, and it is not the same thing as a source
being switched off.

### `SourceAdapterRun` contains one source's failures

Each source is wrapped in a `SourceAdapterRun`, which hands the ingestion a stream that **ends where
the source would have thrown**. Both failure modes are covered: `fetchStations()` throwing outright,
and the stream throwing later while the ingestion pulls from it. The failure is recorded, logged, and
the run continues with the next source.

Two boundaries matter and are tested:

- **Downstream exceptions pass through untouched.** The record is handed to the consumer outside the
  guarded block, so an ingestion that fails is not misreported as a source that failed — and cannot
  let a run advance watermarks for records that were never stored.
- **A failing `close()` counts as the source failing.** A temp file that cannot be released or a
  parser that could not finish means the source did not complete cleanly, so it must not earn
  progress. It is recorded rather than thrown: the run is past that source by then.

### `SyncJob` ingests sources sequentially

`ingestion.upsert(...)` is now called once per source rather than once per run. `BatchedIngestion` is
stateless per call and commits in batches either way, so nothing about batching or transaction size
changes — but three things follow:

- The MDC carries the correct `source` for the whole time that source is being ingested, which fixes
  the scoping bug above. The adapters' own scopes were removed as redundant.
- The result is attributable per source: created, updated, unchanged and failed, logged per source.
- **`commitProgress()` is decided on that source's own outcome** — it runs only when that source both
  delivered everything it had and had zero ingestion failures. This closes ADR 0006's open point 3 for
  the cross-source case: a broken French row no longer costs Open Charge Map a day of progress.

### Run status says what actually happened

- `SUCCEEDED` — every source delivered fully and every record was stored.
- `PARTIAL` — a source failed, or records failed, but real data was committed. `master.sync_run` now
  carries a summary naming the sources that broke, so the error message says which country to look at.
- `FAILED` — every source broke and nothing was ingested. Only then is "partially succeeded" untrue.
- `SKIPPED` — no adapters registered, or all of them disabled. The second case used to be invisible:
  the run reported success having ingested nothing.

An exception from the ingestion itself still fails the whole run, unchanged. That split is the point:
a source is one country's problem, the ingestion is everyone's.

### `sync.support` for shared mechanics

Two helpers now have two real users each:

- `BulkDownload` — buffer a large published file to a temp file, parse it, delete on stream close.
  Both bulk registers work this way and the buffering is load-bearing: the ingestion writes to the
  database as it reads, so parsing the HTTP body directly would hold the connection open for the whole
  ingestion. Extracting it also fixed a diagnosability bug — the wrapper message said only "cannot
  read the downloaded X" while the actionable cause ("the schema changed") lived in the exception's
  cause, and `master.sync_run` records only the message.
- `CsvColumns` — address CSV fields by name, tolerate a missing column, strip the BOM.

The package rule is written into its `package-info`: nothing in `support` may depend on a source
package or know what a charging station is, and things move there once a *second* adapter needs them,
not in anticipation of one.

### The layering is now documented rather than implied

`sync/package-info.java` states the dependency direction (`sync.<source>` → `sync` ← `sync.support`),
why `StationIngestionPort` must never reach an adapter, and what adding a source consists of — three
types with conventional names, plus extending `SourceAdapterRegistrationTests`, which asserts the full
set of adapters and their source tokens so a forgotten registration fails a test instead of shipping
as a container that ingests one country less than it should.

## Verified in the sync container on 2026-07-29

A full run against the real database, all three sources, zero failures:

| Source | Stations | Created | Updated | Unchanged | Duration |
|---|---|---|---|---|---|
| BNetzA | 115.435 | 61.380 | 54.055 | 0 | 1 min 09 s |
| IRVE | 51.082 | 33.286 | 17.796 | 0 | 44 s |
| OCM | 65.974 | 39.247 | 11.258 | 15.469 | 5 min 07 s |
| **Run** | **232.491** | **133.913** | **83.109** | **15.469** | **7 min 00 s** |

Per-source attribution, sequential ingestion and per-source watermarking all behave as designed — OCM
advanced its watermark for all eleven countries off its own clean result.

### One defect this run exposed: nested MDC scopes on the same key

The `source` tag was present on the first log lines of each source and then vanished. `BulkDownload`'s
"Downloading BNetzA register" carried it; "Ingestion progress: 5000 stations processed" did not.
`"Full OCM crawl for DE"` carried it, `"Full OCM crawl for AT"` — logged after ingestion had begun —
did not. Neither did the per-source summary this ADR introduced.

The cause is that `MDC.putCloseable(...).close()` **removes** the key rather than restoring what an
enclosing scope put there. `BatchedIngestion` opened its own scope on `source` per record, which was
correct when one composed stream carried every source's records and each record was the only thing
that knew its origin. Under this ADR the run owns the tag for the whole per-source call, so the inner
scope was redundant — and destructive: the first record deleted the run's own tag, and everything
logged afterwards went out unattributed.

The inner scope was removed, leaving one owner per key, and the hazard is now documented on
`LogContext.scope` itself: never nest two scopes on the same key; nesting *different* keys — `source`
inside `job` — is fine and is why `job` survived. A regression test asserts that `BatchedIngestion`
leaves the caller's context intact; it was confirmed to fail against the old behaviour with exactly
the production symptom.

Worth noting that this was found in production logs rather than by a test — the existing tests asserted
counts and control flow, and nothing asserted on the diagnostic context that ADR 0002 makes a contract.

## Consequences

### Positive

- One flaky upstream costs its own country and nothing else. Tested directly, including the case that
  is invisible in a happy-path test: a stream that fails halfway through being consumed.
- Incremental sources advance on their own merit, which is what makes OCM's watermarking hold up as
  more sources are added.
- A run is now attributable: per-source counts in the logs, failing sources named in `master.sync_run`.
- Adding a source is a package plus one line in a test. Nothing in `sync` names a source.
- The `source` MDC tag is finally present on the log lines it was meant for.

### Negative / accepted risks

- **Sources are ingested sequentially, so a run takes the sum of its sources.** Acceptable at a 24 h
  fixed delay and preferable to overlapping runs writing the same rows. Parallelising is possible
  later — `SourceAdapterRun` is already per source — but would need the ingestion's contention
  behaviour understood first, and there is no reason to buy that now.
- **A source that fails is truncated, not retried.** It contributes what it delivered before breaking
  and the next run tries again. Retrying inside a run risks doubling load on an upstream that is
  already unwell.
- **`PARTIAL` is now a more common outcome**, because it covers a case that previously read as
  `FAILED`. Anything alerting on run status needs to treat `PARTIAL` as "look at the error message",
  not as "fine".

## References

- `evmap_service/src/main/java/de/joinside/evmap_service/sync/package-info.java` — the layering rules
- `SourceAdapterRun` and `SourceAdapterRunTests` — the containment behaviour
- [ADR 0006](0006-open-charge-map-source-adapter.md) — open point 3, closed here for the cross-source case
- [ADR 0007](0007-batched-ingestion-transactions.md) — the batched ingestion this builds on, unchanged
- [ADR 0012](0012-additional-national-charging-registers.md) — the source that made this necessary
