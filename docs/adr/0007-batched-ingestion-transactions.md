# 7. Ingestion commits in batches, and salvages failed batches record by record

- Status: Accepted
- Date: 2026-07-28
- Deciders: Johannes Popp

## Context

`PostgresStationIngestionRepository.upsert()` was annotated `@Transactional` around the entire stream.
That was defensible while no `SourceAdapter` existed and the method never ingested anything. With the
BNetzA and OCM adapters (ADR 0005, ADR 0006) it stopped being defensible: the first run against an
empty database now processes **113.385 stations from BNetzA alone**, plus the OCM crawl, in one
transaction.

The failure mode is bad in every direction:

- A single upstream record that violates a constraint fails its statement, which poisons the whole
  Postgres transaction. The `catch` block logged the offending record and rethrew — correct, because
  after a failed statement nothing else can be executed on that connection — so **everything ingested
  in the minutes before it was rolled back too.**
- The riskiest run is exactly the one that matters most: the cold-start import is the longest, does
  the most inserts, and touches the most unseen upstream data. Losing it leaves an empty database.
- `master.sync_run` counters were dishonest. A run could report thousands of stations `created` and
  then roll them all back, because the counters lived in memory and the bookkeeping write ran in its
  own `REQUIRES_NEW` transaction that committed regardless.
- Recovery was all-or-nothing: no partial progress meant every retry started from zero.

## Decision

**Commit in batches of `evmap.sync.batch-size` (default 1.000) stations, and when a batch fails,
retry its records individually so only the genuinely bad ones are lost.**

### Batching

`upsert()` is no longer `@Transactional`. It buffers the incoming stream into batches and commits
each through a `TransactionTemplate` — a template rather than `@Transactional` on a helper method,
because a batch is committed from inside the same class and a self-invocation never passes through
Spring's transactional proxy.

The stream is consumed lazily throughout: a run never holds more than one batch in memory, which is
what makes a 113k-station import viable at all.

### Per-record salvage

A failed batch is retried one record per transaction. The cost — a transaction per record — is only
ever paid on the batch that actually broke, and it turns "one bad record costs 1.000 stations" into
"one bad record costs one station". The run continues afterwards rather than aborting, because a
handful of malformed upstream rows should not cost the other 113.000.

### Counting only what committed

Batch outcomes are counted into a scratch counter that is merged into the run totals **only after the
commit returns**. A rolled-back batch — or one the transaction manager retried — can no longer
overstate what was ingested. The nine healthy records in a poisoned batch of ten are attempted twice
but counted once.

### A status for "finished, but not everything"

`master.sync_run` gains a `failed` column and the status vocabulary gains `PARTIAL`
(migration `003-ingestion-resilience.sql`). A run that committed its data but lost individual records
is neither `SUCCEEDED` nor `FAILED`, and flattening it into either loses the distinction that matters
when reading the history.

`IngestionHealthIndicator` treats `PARTIAL` as a success for staleness purposes — such a run did
ingest data — while surfacing the `failed` count in the health details. Reporting the whole ingestion
DOWN over a few bad rows would be noise; hiding them entirely would be worse.

### Batching is also the precondition for incremental sync

Because partial progress now survives, `SourceAdapter.commitProgress()` exists and is called only
when a run completed with zero failures. That is what lets the OCM adapter advance a watermark
safely (ADR 0006): "the ingestion committed everything I handed it" is now a statement the job can
actually make.

### The logic is extracted so it can be tested

Batching, counting and failure isolation live in `BatchedIngestion`, separate from the repository.
The repository's own logic needs PostGIS to mean anything — `ST_DWithin` deduplication, generated
geography columns — and there is no Testcontainers setup in this module, so a database-bound design
would have shipped this untested. The extracted class is covered by seven tests including the
central one: a poisoned record in a batch of ten costs only itself, and the other 99 of 100 stations
are still committed.

## Consequences

### Positive

- A bad upstream record costs one station instead of the entire run.
- An interrupted run leaves the work it had already done; because ingestion is an upsert, the next
  run simply completes it. No separate resume mechanism is needed.
- Run counters now describe what is actually in the database.
- Memory is bounded by batch size rather than by the size of the upstream dataset.
- A dedicated one-off import path is unnecessary: `@Scheduled(fixedDelayString = …)` without an
  initial delay already runs immediately on container start, so the first scheduled run *is* the
  initial import — and it is now safe to interrupt.

### Negative / accepted risks

- **A run is no longer atomic.** A failure partway leaves the database holding some of this run's
  data and some of the previous run's. For a read-mostly registry of installed infrastructure that is
  strictly better than an empty table, but it is a real change in guarantee: there is no longer any
  instant at which the database reflects exactly one upstream snapshot.
- **Batch failures cost a retry pass.** A batch that fails on its last record re-executes all of it
  as individual transactions. Pathologically, a source failing on most records degrades to one
  transaction per record for the whole run.
- **`failed` counts records, not causes.** The log carries the exception per record; the run history
  only carries how many. Deliberate — the alternative is a table of ingestion errors, which is more
  machinery than a handful of bad rows justifies today.
- **Deletions are still not propagated**, unchanged by this ADR: a station that disappears upstream
  stays in `master`.

## Alternatives considered

**Keep one transaction, fix the bad records.** Requires knowing every constraint every upstream source
will ever violate. The register is 113k community- and operator-maintained rows; that is not knowable
in advance, and the penalty for being wrong is an empty database.

**Batch, but abort the whole run on the first failed batch.** Simpler, and partial progress would
still survive. Rejected because it makes total coverage hostage to the single worst record in the
dataset — the run would stop at the same place every day until someone intervened.

**Skip individual records without the batch retry**, by committing one transaction per record
throughout. Correct and much simpler, but a transaction per station across 113k stations is a
substantial cost paid on every run to handle a case that arises rarely.

## References

- `evmap_service/src/main/java/de/joinside/evmap_service/sync/BatchedIngestion.java`
- `evmap_service/src/main/resources/db/changelog/003-ingestion-resilience.sql`
- [ADR 0004](0004-ingestion-run-history-and-health-reporting.md) — the run history this extends
- [ADR 0005](0005-bundesnetzagentur-source-adapter.md), [ADR 0006](0006-open-charge-map-source-adapter.md)
  — the adapters whose volume made this necessary
