package de.joinside.evmap_service.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Splits an ingestion run into independently committed batches, and salvages a failed batch record by
 * record.
 * <p>
 * Separated from the repository because this is the part with the interesting behaviour and none of
 * the database: the repository's own logic needs PostGIS to mean anything, while batching, counting
 * and failure isolation can be exercised on their own. See ADR 0007.
 */
final class BatchedIngestion {
    private static final Logger log = LoggerFactory.getLogger(BatchedIngestion.class);
    /** Progress heartbeat, so a long ingestion run does not look hung in `docker logs`. */
    private static final int PROGRESS_INTERVAL = 5_000;

    /** What ingesting one station did to master data. */
    enum Outcome {CREATED, UPDATED, UNCHANGED}

    /** Runs one unit of work atomically; throws if it could not be committed. */
    @FunctionalInterface
    interface Transaction {
        void run(Runnable work);
    }

    private BatchedIngestion() {
    }

    static StationIngestionPort.IngestionResult run(Stream<SourceStation> stations,
                                                    int batchSize,
                                                    Transaction transaction,
                                                    Function<SourceStation, Outcome> upsert) {
        int size = Math.max(1, batchSize);
        Counters totals = new Counters();
        List<SourceStation> batch = new ArrayList<>(size);
        int[] lastLogged = {0};

        stations.forEach(station -> {
            batch.add(station);
            if (batch.size() >= size) {
                flush(batch, totals, transaction, upsert);
                batch.clear();
                lastLogged[0] = logProgress(totals, lastLogged[0]);
            }
        });
        if (!batch.isEmpty()) flush(batch, totals, transaction, upsert);

        return totals.toResult();
    }

    /**
     * Commits one batch, and falls back to one transaction per record if it fails.
     * <p>
     * The retry costs a transaction per record, but only ever on the batch that actually broke:
     * without it a single unmappable station would take the other {@code batch-size - 1} with it,
     * since a Postgres transaction is unusable after a failed statement.
     */
    private static void flush(List<SourceStation> batch, Counters totals,
                              Transaction transaction, Function<SourceStation, Outcome> upsert) {
        Counters counted = new Counters();
        try {
            // Counted into a scratch instance and merged only once the commit returned, so a
            // rolled-back batch — or one the transaction manager retried — cannot overstate the totals.
            transaction.run(() -> {
                counted.reset();
                for (SourceStation station : batch) ingestOne(counted, station, upsert);
            });
            totals.add(counted);
        } catch (RuntimeException exception) {
            log.warn("Batch of {} stations failed ({}), retrying individually to isolate the bad records",
                    batch.size(), rootCauseOf(exception));
            retryIndividually(batch, totals, transaction, upsert);
        }
    }

    private static void retryIndividually(List<SourceStation> batch, Counters totals,
                                          Transaction transaction, Function<SourceStation, Outcome> upsert) {
        for (SourceStation station : batch) {
            Counters counted = new Counters();
            try {
                transaction.run(() -> {
                    counted.reset();
                    ingestOne(counted, station, upsert);
                });
                totals.add(counted);
            } catch (RuntimeException exception) {
                totals.failed++;
                log.error("Skipped {}/{} ({}): {}", station.source(), station.sourceStationId(),
                        station.name(), rootCauseOf(exception));
            }
        }
    }

    /**
     * Spring wraps driver failures, and the wrapper's own message names only the SQL. The cause
     * carries what actually went wrong — without it, a driver-side rejection such as an unsupported
     * parameter type reads as an unexplained "bad SQL grammar" on a statement that is in fact valid.
     */
    private static String rootCauseOf(Throwable exception) {
        Throwable cause = exception;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause == exception
                ? exception.toString()
                : exception + " | cause: " + cause;
    }

    /**
     * Deliberately does <em>not</em> scope the MDC to {@code station.source()}.
     * <p>
     * It used to, back when one composed stream carried every source's records and each record was the
     * only thing that knew where it came from. That is no longer true — the run ingests one source at a
     * time and owns the {@code source} tag for the whole call (ADR 0013) — and re-setting it here was
     * actively destructive: {@code MDC.putCloseable} removes the key on close instead of restoring the
     * previous value, so the first record silently deleted the run's own tag. Every log line after it,
     * including the ingestion progress and the per-source summary, went out unattributed.
     */
    private static void ingestOne(Counters counters, SourceStation station, Function<SourceStation, Outcome> upsert) {
        counters.count(upsert.apply(station));
    }

    /** Heartbeat on whole intervals rather than per batch, so batch size does not drive log volume. */
    private static int logProgress(Counters totals, int lastLogged) {
        if (totals.attempted() - lastLogged < PROGRESS_INTERVAL) return lastLogged;
        log.info("Ingestion progress: {} stations processed ({} created, {} updated, {} failed)",
                totals.attempted(), totals.created, totals.updated, totals.failed);
        return totals.attempted();
    }

    private static final class Counters {
        private int processed;
        private int created;
        private int updated;
        private int failed;

        void reset() {
            processed = created = updated = 0;
        }

        void count(Outcome outcome) {
            processed++;
            switch (outcome) {
                case CREATED -> created++;
                case UPDATED -> updated++;
                case UNCHANGED -> {
                    // Counted as processed above and nowhere else: nothing was written.
                }
            }
        }

        void add(Counters other) {
            processed += other.processed;
            created += other.created;
            updated += other.updated;
            failed += other.failed;
        }

        /** Everything the run tried to ingest, whether or not it survived. */
        int attempted() {
            return processed + failed;
        }

        StationIngestionPort.IngestionResult toResult() {
            return new StationIngestionPort.IngestionResult(attempted(), created, updated,
                    processed - created - updated, failed);
        }
    }
}
