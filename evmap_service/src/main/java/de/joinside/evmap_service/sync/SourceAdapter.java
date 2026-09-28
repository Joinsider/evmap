package de.joinside.evmap_service.sync;

import java.util.stream.Stream;

/**
 * One external data source, normalized onto {@link SourceStation}.
 * <p>
 * Implementations live in their own sub-package of {@code sync} and know nothing about each other, the
 * ingestion, or the run they take part in. See the package documentation for the layering this rests on.
 */
public interface SourceAdapter {
    /**
     * Stable token identifying this source, written into {@code master.charging_station_source.source}
     * and shown to users as the provenance of a station.
     * <p>
     * Must equal the {@code source} of every {@link SourceStation} this adapter emits: the run tags its
     * log lines with it, the ingestion attributes records by it, and {@link SyncStateStore} scopes
     * watermarks by it. Kept to the 32 characters the column holds.
     */
    String source();

    /**
     * Whether this source takes part in a run at all. Checked centrally so a disabled source is
     * reported once, in one place, instead of each adapter inventing its own skip message.
     * <p>
     * This is the operator-facing on/off switch. A source that is enabled but cannot run today — a
     * missing API key, say — is not this: it belongs in {@link #fetchStations()}, which may return an
     * empty stream rather than failing the run.
     */
    default boolean enabled() {
        return true;
    }

    /**
     * The stations this source currently offers.
     * <p>
     * Preferably lazy: records are consumed as the ingestion commits them, so a source that fails
     * halfway keeps whatever it already delivered. Whatever the stream holds open — a buffered
     * download, an in-flight crawl — must be released by {@code close()}, which the run always calls.
     */
    Stream<SourceStation> fetchStations();

    /**
     * Called once after the ingestion committed everything this source produced, and only then.
     * <p>
     * An adapter that fetches incrementally advances its watermark here rather than while crawling.
     * Records leave {@link #fetchStations()} long before they are committed, so a watermark moved
     * during the crawl would skip anything that was fetched and then lost to a failure.
     */
    default void commitProgress() {
    }
}
