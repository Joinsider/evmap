package de.joinside.evmap_service.sync;

import de.joinside.evmap_service.logging.LogContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class BatchedIngestionTests {

    @Test
    @DisplayName("leaves the caller's diagnostic context intact, because the run owns the source tag")
    void doesNotClearTheCallersMdc() {
        // Regression: this used to open its own MDC scope on `source` per record. MDC.putCloseable
        // removes the key on close rather than restoring the enclosing value, so the very first record
        // deleted the tag SyncJob had set for the whole source — and every line after it, including the
        // ingestion progress and the per-source summary, went out unattributed. Caught in production
        // logs, not by a test, which is why there is one now.
        try (var scope = LogContext.scope(LogContext.SOURCE, "BNetzA")) {
            BatchedIngestion.run(stations(3), 2, new CommittingTransactions(),
                    ignored -> BatchedIngestion.Outcome.CREATED);

            assertThat(MDC.get(LogContext.SOURCE)).isEqualTo("BNetzA");
        }
        assertThat(MDC.get(LogContext.SOURCE)).isNull();
    }

    private static SourceStation station(int id) {
        return new SourceStation("TEST", String.valueOf(id), "Station " + id, "Weg 1", "Berlin", "10115",
                "DE", "Betreiber", 52.5, 13.4, AvailabilityStatus.OPERATIONAL, Instant.EPOCH, List.of());
    }

    private static Stream<SourceStation> stations(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(BatchedIngestionTests::station);
    }

    /** Commits everything; records what was committed so tests can assert on durability. */
    private static final class CommittingTransactions implements BatchedIngestion.Transaction {
        private int commits;

        @Override
        public void run(Runnable work) {
            work.run();
            commits++;
        }
    }

    /** Rolls back — i.e. throws — for batches containing a poisoned station, like Postgres would. */
    private record PoisonedTransactions(Set<String> poison, List<String> committed)
            implements BatchedIngestion.Transaction {

        @Override
        public void run(Runnable work) {
            List<String> before = List.copyOf(committed);
            work.run();
            if (committed.stream().anyMatch(poison::contains)) {
                // A rolled-back transaction leaves nothing behind, including the records that were fine.
                committed.clear();
                committed.addAll(before);
                throw new IllegalStateException("constraint violation");
            }
        }
    }

    @Test
    @DisplayName("commits in batches rather than as one transaction")
    void commitsInBatches() {
        CommittingTransactions transactions = new CommittingTransactions();

        var result = BatchedIngestion.run(stations(250), 100, transactions,
                station -> BatchedIngestion.Outcome.CREATED);

        // 100 + 100 + 50 — the trailing partial batch must not be dropped.
        assertThat(transactions.commits).isEqualTo(3);
        assertThat(result.processed()).isEqualTo(250);
        assertThat(result.created()).isEqualTo(250);
        assertThat(result.failed()).isZero();
    }

    @Test
    @DisplayName("counts each outcome separately")
    void countsOutcomes() {
        Function<SourceStation, BatchedIngestion.Outcome> byId = station -> switch (
                Integer.parseInt(station.sourceStationId()) % 3) {
            case 0 -> BatchedIngestion.Outcome.CREATED;
            case 1 -> BatchedIngestion.Outcome.UPDATED;
            default -> BatchedIngestion.Outcome.UNCHANGED;
        };

        var result = BatchedIngestion.run(stations(9), 4, new CommittingTransactions(), byId);

        assertThat(result.processed()).isEqualTo(9);
        assertThat(result.created()).isEqualTo(3);
        assertThat(result.updated()).isEqualTo(3);
        assertThat(result.unchanged()).isEqualTo(3);
        assertThat(result.failed()).isZero();
    }

    @Test
    @DisplayName("one bad record costs only itself, not its batch and not the run")
    void isolatesABadRecord() {
        // This is the whole point of the change: before batching, station 42 would have discarded
        // every station ingested before it and left an empty database.
        List<String> committed = new ArrayList<>();
        var transactions = new PoisonedTransactions(Set.of("42"), committed);

        var result = BatchedIngestion.run(stations(100), 10, transactions, station -> {
            committed.add(station.sourceStationId());
            return BatchedIngestion.Outcome.CREATED;
        });

        assertThat(result.processed()).isEqualTo(100);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.created()).isEqualTo(99);
        // Everything except the poisoned record survived, including the nine others in its batch.
        assertThat(committed).hasSize(99).doesNotContain("42").contains("41", "43", "1", "100");
    }

    @Test
    @DisplayName("keeps going after a failure instead of abandoning the rest of the run")
    void continuesPastFailures() {
        List<String> committed = new ArrayList<>();
        var transactions = new PoisonedTransactions(Set.of("5", "55", "95"), committed);

        var result = BatchedIngestion.run(stations(100), 10, transactions, station -> {
            committed.add(station.sourceStationId());
            return BatchedIngestion.Outcome.CREATED;
        });

        assertThat(result.failed()).isEqualTo(3);
        assertThat(result.created()).isEqualTo(97);
        assertThat(committed).hasSize(97).doesNotContain("5", "55", "95");
    }

    @Test
    @DisplayName("does not count work that was rolled back")
    void doesNotCountRolledBackWork() {
        // The nine healthy records in the poisoned batch are attempted twice — once in the batch that
        // rolls back, once individually. Counting the first attempt would report 109 of 100 ingested.
        List<String> committed = new ArrayList<>();
        var transactions = new PoisonedTransactions(Set.of("42"), committed);

        var result = BatchedIngestion.run(stations(100), 10, transactions, station -> {
            committed.add(station.sourceStationId());
            return BatchedIngestion.Outcome.CREATED;
        });

        assertThat(result.processed()).isEqualTo(100);
        assertThat(result.created() + result.failed()).isEqualTo(100);
        assertThat(committed).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("survives a batch size larger than the run, and an empty run")
    void handlesEdgeSizes() {
        var oversized = BatchedIngestion.run(stations(3), 10_000, new CommittingTransactions(),
                station -> BatchedIngestion.Outcome.CREATED);
        assertThat(oversized.processed()).isEqualTo(3);

        var empty = BatchedIngestion.run(Stream.of(), 100, new CommittingTransactions(),
                station -> BatchedIngestion.Outcome.CREATED);
        assertThat(empty.processed()).isZero();
        assertThat(empty.failed()).isZero();

        // A nonsensical batch size must degrade to one-at-a-time, not divide by zero or buffer forever.
        var zeroSized = BatchedIngestion.run(stations(3), 0, new CommittingTransactions(),
                station -> BatchedIngestion.Outcome.CREATED);
        assertThat(zeroSized.processed()).isEqualTo(3);
    }

    @Test
    @DisplayName("consumes the source stream lazily, so a run never buffers every station")
    void streamsLazily() {
        // 113k BNetzA stations must not be materialised before the first commit.
        int[] peakOutstanding = {0};
        int[] outstanding = {0};

        Stream<SourceStation> lazy = stations(1_000).peek(station -> {
            outstanding[0]++;
            peakOutstanding[0] = Math.max(peakOutstanding[0], outstanding[0]);
        });

        BatchedIngestion.run(lazy, 100, work -> {
            work.run();
            outstanding[0] = 0;
        }, station -> BatchedIngestion.Outcome.CREATED);

        assertThat(peakOutstanding[0]).isLessThanOrEqualTo(100);
    }
}
