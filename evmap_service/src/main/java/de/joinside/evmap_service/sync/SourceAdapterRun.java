package de.joinside.evmap_service.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * One source's contribution to a sync run: its records, counted, with its failures contained.
 * <p>
 * Containment is the point. Sources are independent — an unreachable national portal says nothing
 * about the other countries — but a stream composed across adapters is not: one exception anywhere in
 * it ends the whole ingestion. With two sources that was a tolerable simplification; at six it means
 * any one flaky upstream costs every other country its daily update. So a failing source is recorded
 * and truncated here, and the run carries on with the rest. See ADR 0013.
 * <p>
 * Only failures <em>from the source</em> are caught. An exception raised downstream — the ingestion
 * itself failing — passes through untouched, because that is a run-level problem and not this
 * source's fault.
 */
final class SourceAdapterRun {
    private static final Logger log = LoggerFactory.getLogger(SourceAdapterRun.class);

    private final SourceAdapter adapter;
    private long fetched;
    private RuntimeException failure;
    private StationIngestionPort.IngestionResult result = StationIngestionPort.IngestionResult.none();

    SourceAdapterRun(SourceAdapter adapter) {
        this.adapter = adapter;
    }

    String source() {
        return adapter.source();
    }

    /** Records emitted by the source, which is not the same as records the ingestion accepted. */
    long fetched() {
        return fetched;
    }

    void setResult(StationIngestionPort.IngestionResult result) {
        this.result = result;
    }

    /** What the ingestion did with this source's records; empty until it ran. */
    StationIngestionPort.IngestionResult result() {
        return result;
    }

    /** Whether the source delivered everything it had. A truncated source must not commit progress. */
    boolean succeeded() {
        return failure == null;
    }

    RuntimeException failure() {
        return failure;
    }

    /**
     * The source's records, as a stream that ends quietly where the source would have thrown.
     * <p>
     * Both failure modes are covered: {@link SourceAdapter#fetchStations()} throwing outright — a
     * download that 404s, a page layout that no longer parses — and the stream throwing later, while
     * the ingestion pulls from it.
     */
    Stream<SourceStation> stations() {
        Stream<SourceStation> upstream;
        try {
            upstream = adapter.fetchStations();
        } catch (RuntimeException exception) {
            recordFailure(exception);
            return Stream.empty();
        }
        if (upstream == null) return Stream.empty();

        Spliterator<SourceStation> source = upstream.spliterator();
        // Neither SIZED nor SUBSIZED survives: this spliterator may stop early, so any size the source
        // advertised would become a lie the moment a failure truncates it.
        Spliterator<SourceStation> guarded = new Spliterators.AbstractSpliterator<>(
                source.estimateSize(),
                source.characteristics() & ~(Spliterator.SIZED | Spliterator.SUBSIZED)) {

            @Override
            public boolean tryAdvance(Consumer<? super SourceStation> action) {
                if (failure != null) return false;
                SourceStation next = pull(source);
                if (next == null) return false;

                fetched++;
                // Deliberately outside pull()'s try: an exception from here comes from the ingestion,
                // and swallowing it would report a broken database as a broken data source.
                action.accept(next);
                return true;
            }
        };

        return StreamSupport.stream(guarded, false).onClose(() -> closeQuietly(upstream));
    }

    /**
     * The source's next record, or {@code null} when it is exhausted or has just failed.
     * <p>
     * Pulled into a holder rather than handed straight to the consumer, so that only the source's own
     * work runs inside the {@code try}; the consumer is the ingestion and must not be caught here.
     */
    private SourceStation pull(Spliterator<SourceStation> source) {
        List<SourceStation> holder = new ArrayList<>(1);
        try {
            source.tryAdvance(holder::add);
        } catch (RuntimeException exception) {
            recordFailure(exception);
            return null;
        }
        return holder.isEmpty() ? null : holder.getFirst();
    }

    /**
     * Closing releases the source's own resources — a buffered download, a temp file, an open reader —
     * so a failure here means the source did not finish cleanly even if every record arrived. It is
     * recorded rather than thrown: the run is past this source by now, and the remaining ones are
     * unaffected.
     */
    private void closeQuietly(Stream<SourceStation> upstream) {
        try {
            upstream.close();
        } catch (RuntimeException exception) {
            recordFailure(exception);
        }
    }

    /** Keeps the first failure: it is the one that explains the truncation, later ones are fallout. */
    private void recordFailure(RuntimeException exception) {
        if (failure != null) return;
        failure = exception;
        log.error("Source {} failed after {} station(s) and contributed nothing further: {}",
                adapter.source(), fetched, exception.getMessage(), exception);
    }
}
