package de.joinside.evmap_service.sync;

import java.util.stream.Stream;

public interface SourceAdapter {
    Stream<SourceStation> fetchStations();

    /**
     * Called once after the ingestion committed everything this run produced, and only then.
     * <p>
     * An adapter that fetches incrementally advances its watermark here rather than while crawling.
     * Records leave {@link #fetchStations()} long before they are committed, so a watermark moved
     * during the crawl would skip anything that was fetched and then lost to a failure.
     */
    default void commitProgress() {
    }
}
