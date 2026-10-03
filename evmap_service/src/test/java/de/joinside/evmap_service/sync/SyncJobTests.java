package de.joinside.evmap_service.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What one sync run records in {@code master.sync_run}, and which sources may advance their watermark.
 * <p>
 * The port is a recording fake rather than a database: the decisions under test — status, failure
 * summary, commit or hold back — are all made here, and the SQL behind the port has its own tests.
 */
class SyncJobTests {

    private static SourceStation station(String source, String id) {
        return new SourceStation(source, id, "Station " + id, "Street 1", "Town", "12345", "DE",
                "Operator", 48.0, 9.0, null, null, List.of());
    }

    /** A source with a fixed number of records that fails after {@code failAfter}; never when negative. */
    private static final class FakeAdapter implements SourceAdapter {
        private final String source;
        private final int available;
        private final int failAfter;
        private final boolean enabled;
        private boolean committed;

        FakeAdapter(String source, int available, int failAfter, boolean enabled) {
            this.source = source;
            this.available = available;
            this.failAfter = failAfter;
            this.enabled = enabled;
        }

        static FakeAdapter healthy(String source, int available) {
            return new FakeAdapter(source, available, -1, true);
        }

        @Override
        public String source() {
            return source;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public Stream<SourceStation> fetchStations() {
            return Stream.iterate(0, index -> index + 1).limit(available).map(index -> {
                if (failAfter >= 0 && index >= failAfter) throw new IllegalStateException(source + " went away");
                return station(source, String.valueOf(index));
            });
        }

        @Override
        public void commitProgress() {
            committed = true;
        }
    }

    /** Records every call; {@code failPerUpsert} marks that many records of each upsert as failed. */
    private static final class RecordingPort implements StationIngestionPort {
        private final UUID runId = UUID.randomUUID();
        private final int failPerUpsert;
        private final boolean breakOnUpsert;
        private final List<String> ingested = new ArrayList<>();
        private RunStatus status;
        private IngestionResult result;
        private String errorMessage;

        RecordingPort(int failPerUpsert, boolean breakOnUpsert) {
            this.failPerUpsert = failPerUpsert;
            this.breakOnUpsert = breakOnUpsert;
        }

        @Override
        public IngestionResult upsert(Stream<SourceStation> stations) {
            if (breakOnUpsert) throw new IllegalStateException("database is gone");
            List<SourceStation> all = stations.toList();
            all.forEach(station -> ingested.add(station.source() + "/" + station.sourceStationId()));
            int failed = Math.min(failPerUpsert, all.size());
            return new IngestionResult(all.size(), all.size() - failed, 0, 0, failed);
        }

        @Override
        public int supersedeDuplicates(String source) {
            ingested.add("supersede:" + source);
            return 7;
        }

        @Override
        public UUID startRun() {
            return runId;
        }

        @Override
        public void finishRun(UUID runId, RunStatus status, IngestionResult result, String errorMessage) {
            assertThat(runId).isEqualTo(this.runId);
            this.status = status;
            this.result = result;
            this.errorMessage = errorMessage;
        }
    }

    @Test
    @DisplayName("a run where every source delivered everything succeeds and commits every watermark")
    void succeeds() {
        FakeAdapter bnetza = FakeAdapter.healthy("BNETZA", 3);
        FakeAdapter ocm = FakeAdapter.healthy("OCM", 2);
        RecordingPort port = new RecordingPort(0, false);

        new SyncJob(List.of(bnetza, ocm), port).synchronize();

        assertThat(port.status).isEqualTo(StationIngestionPort.RunStatus.SUCCEEDED);
        assertThat(port.result).isEqualTo(new StationIngestionPort.IngestionResult(5, 5, 0, 0, 0));
        assertThat(port.errorMessage).isNull();
        assertThat(port.ingested).hasSize(5);
        assertThat(bnetza.committed).isTrue();
        assertThat(ocm.committed).isTrue();
    }

    @Test
    @DisplayName("after all sources, the superseding sources recompute their marks — configured, never named here")
    void supersedesAfterAllSources() {
        FakeAdapter bnetza = FakeAdapter.healthy("BNETZA", 1);
        FakeAdapter mobilithek = FakeAdapter.healthy("MOBILITHEK", 1);
        RecordingPort port = new RecordingPort(0, false);

        new SyncJob(List.of(mobilithek, bnetza), port, List.of(" MOBILITHEK ", "")).synchronize();

        assertThat(port.ingested).containsExactly("MOBILITHEK/0", "BNETZA/0", "supersede:MOBILITHEK");
        assertThat(port.status).isEqualTo(StationIngestionPort.RunStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("a broken source is truncated and named; the others are still ingested (ADR 0013)")
    void containsABrokenSource() {
        FakeAdapter broken = new FakeAdapter("IRVE", 10, 2, true);
        FakeAdapter healthy = FakeAdapter.healthy("OCM", 2);
        RecordingPort port = new RecordingPort(0, false);

        new SyncJob(List.of(broken, healthy), port).synchronize();

        assertThat(port.status).isEqualTo(StationIngestionPort.RunStatus.PARTIAL);
        assertThat(port.result.processed()).isEqualTo(4);
        assertThat(port.errorMessage).startsWith("IRVE: ").contains("IRVE went away");
        // Only the source that delivered everything may move its watermark.
        assertThat(broken.committed).isFalse();
        assertThat(healthy.committed).isTrue();
    }

    @Test
    @DisplayName("a source whose records failed to store keeps its watermark and makes the run partial")
    void holdsBackOnIngestionFailures() {
        FakeAdapter source = FakeAdapter.healthy("OCM", 3);
        RecordingPort port = new RecordingPort(1, false);

        new SyncJob(List.of(source), port).synchronize();

        assertThat(port.status).isEqualTo(StationIngestionPort.RunStatus.PARTIAL);
        assertThat(port.result.failed()).isEqualTo(1);
        assertThat(port.errorMessage).isNull();
        assertThat(source.committed).isFalse();
    }

    @Test
    @DisplayName("a run is failed only when every source broke and nothing was ingested")
    void failsWhenNothingArrived() {
        RecordingPort port = new RecordingPort(0, false);

        new SyncJob(List.of(new FakeAdapter("A", 5, 0, true), new FakeAdapter("B", 5, 0, true)), port).synchronize();

        assertThat(port.status).isEqualTo(StationIngestionPort.RunStatus.FAILED);
        assertThat(port.errorMessage).contains("A: ").contains("; B: ");
    }

    @Test
    @DisplayName("a broken ingestion fails the run instead of being blamed on a source")
    void recordsABrokenIngestion() {
        FakeAdapter source = FakeAdapter.healthy("OCM", 3);
        RecordingPort port = new RecordingPort(0, true);

        new SyncJob(List.of(source), port).synchronize();

        assertThat(port.status).isEqualTo(StationIngestionPort.RunStatus.FAILED);
        assertThat(port.result).isNull();
        assertThat(port.errorMessage).contains("database is gone");
        assertThat(source.committed).isFalse();
    }

    @Test
    @DisplayName("a run with nothing to do is recorded as skipped, with the reason")
    void skipsWithoutSources() {
        RecordingPort none = new RecordingPort(0, false);
        new SyncJob(List.of(), none).synchronize();
        assertThat(none.status).isEqualTo(StationIngestionPort.RunStatus.SKIPPED);
        assertThat(none.errorMessage).isEqualTo("No source adapters registered");

        RecordingPort disabled = new RecordingPort(0, false);
        new SyncJob(List.of(new FakeAdapter("OCM", 3, -1, false)), disabled).synchronize();
        assertThat(disabled.status).isEqualTo(StationIngestionPort.RunStatus.SKIPPED);
        assertThat(disabled.errorMessage).isEqualTo("Every registered source adapter is disabled");
        assertThat(disabled.ingested).isEmpty();
    }
}
