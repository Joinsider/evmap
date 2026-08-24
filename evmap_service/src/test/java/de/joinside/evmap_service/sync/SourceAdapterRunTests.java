package de.joinside.evmap_service.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The containment rule this class exists for: a broken source costs its own records and nothing else.
 * <p>
 * Worth testing on its own rather than only through {@code SyncJob}, because the interesting case —
 * a stream that throws halfway through being consumed — is invisible in a happy-path integration test
 * and is exactly what an unreachable national portal produces.
 */
class SourceAdapterRunTests {

    private static SourceStation station(String id) {
        return new SourceStation("TEST", id, "Station " + id, "Street 1", "Town", "12345", "DE",
                "Operator", 48.0, 9.0, null, null, List.of());
    }

    /** An adapter whose stream fails after {@code failAfter} records; never, when negative. */
    private record FlakyAdapter(String source, int available, int failAfter) implements SourceAdapter {
        @Override
        public Stream<SourceStation> fetchStations() {
            return Stream.iterate(0, index -> index + 1)
                    .limit(available)
                    .map(index -> {
                        if (failAfter >= 0 && index >= failAfter)
                            throw new IllegalStateException("upstream went away at " + index);
                        return station(String.valueOf(index));
                    });
        }
    }

    @Test
    @DisplayName("passes a healthy source through untouched")
    void passesHealthyRecordsThrough() {
        SourceAdapterRun run = new SourceAdapterRun(new FlakyAdapter("TEST", 3, -1));

        assertThat(run.stations().map(SourceStation::sourceStationId)).containsExactly("0", "1", "2");
        assertThat(run.succeeded()).isTrue();
        assertThat(run.fetched()).isEqualTo(3);
        assertThat(run.failure()).isNull();
    }

    @Test
    @DisplayName("keeps what a source delivered before it broke, instead of losing the whole run")
    void truncatesAtTheFailure() {
        SourceAdapterRun run = new SourceAdapterRun(new FlakyAdapter("TEST", 100, 2));

        // The consumer sees a stream that simply ends — no exception reaches the ingestion.
        assertThat(run.stations().map(SourceStation::sourceStationId)).containsExactly("0", "1");
        assertThat(run.succeeded()).isFalse();
        assertThat(run.fetched()).isEqualTo(2);
        assertThat(run.failure()).hasMessageContaining("upstream went away at 2");
    }

    @Test
    @DisplayName("contains a source that fails before producing anything at all")
    void containsAnImmediateFailure() {
        SourceAdapterRun run = new SourceAdapterRun(new SourceAdapter() {
            @Override
            public String source() {
                return "TEST";
            }

            @Override
            public Stream<SourceStation> fetchStations() {
                // What a 404 on the download, or a changed page layout, looks like.
                throw new IllegalStateException("download failed with 404 NOT_FOUND");
            }
        });

        assertThat(run.stations()).isEmpty();
        assertThat(run.succeeded()).isFalse();
        assertThat(run.failure()).hasMessageContaining("404");
    }

    @Test
    @DisplayName("lets an ingestion failure through, because that is not the source's fault")
    void doesNotSwallowDownstreamFailures() {
        SourceAdapterRun run = new SourceAdapterRun(new FlakyAdapter("TEST", 5, -1));

        // Swallowing this would report a broken database as a broken data source, and would let the
        // run advance watermarks for records that were never stored.
        assertThatThrownBy(() -> run.stations().forEach(station -> {
            throw new IllegalStateException("database is down");
        })).isInstanceOf(IllegalStateException.class).hasMessage("database is down");

        assertThat(run.succeeded()).isTrue();
    }

    @Test
    @DisplayName("closes the source's stream, and treats a failing close as the source failing")
    void closesAndRecordsCloseFailures() {
        AtomicBoolean closed = new AtomicBoolean();
        SourceAdapterRun clean = new SourceAdapterRun(new SourceAdapter() {
            @Override
            public String source() {
                return "TEST";
            }

            @Override
            public Stream<SourceStation> fetchStations() {
                return Stream.of(station("1")).onClose(() -> closed.set(true));
            }
        });
        clean.stations().close();
        assertThat(closed).isTrue();
        assertThat(clean.succeeded()).isTrue();

        // A temp file that cannot be released, or a parser that could not finish, means the source did
        // not complete cleanly — so it must not be allowed to advance an incremental watermark.
        SourceAdapterRun dirty = new SourceAdapterRun(new SourceAdapter() {
            @Override
            public String source() {
                return "TEST";
            }

            @Override
            public Stream<SourceStation> fetchStations() {
                return Stream.of(station("1")).onClose(() -> {
                    throw new IllegalStateException("cannot delete the download");
                });
            }
        });
        dirty.stations().close();
        assertThat(dirty.succeeded()).isFalse();
        assertThat(dirty.failure()).hasMessageContaining("cannot delete the download");
    }

    @Test
    @DisplayName("keeps the first failure, which is the one that explains the truncation")
    void keepsTheFirstFailure() {
        SourceAdapterRun run = new SourceAdapterRun(new SourceAdapter() {
            @Override
            public String source() {
                return "TEST";
            }

            @Override
            public Stream<SourceStation> fetchStations() {
                return Stream.<SourceStation>generate(() -> {
                    throw new IllegalStateException("first");
                }).onClose(() -> {
                    throw new IllegalStateException("fallout from the first");
                });
            }
        });

        try (Stream<SourceStation> stations = run.stations()) {
            assertThat(stations).isEmpty();
        }
        assertThat(run.failure()).hasMessage("first");
    }

    @Test
    @DisplayName("an adapter returning no stream at all contributes nothing rather than crashing the run")
    void toleratesANullStream() {
        SourceAdapterRun run = new SourceAdapterRun(new SourceAdapter() {
            @Override
            public String source() {
                return "TEST";
            }

            @Override
            public Stream<SourceStation> fetchStations() {
                return null;
            }
        });

        assertThat(run.stations()).isEmpty();
        assertThat(run.succeeded()).isTrue();
    }
}
