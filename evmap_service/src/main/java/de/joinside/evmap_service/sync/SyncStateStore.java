package de.joinside.evmap_service.sync;

import java.time.Instant;
import java.util.Optional;

/**
 * High-water marks that let an adapter fetch only what changed since its last successful run.
 * <p>
 * Deliberately separate from {@link StationIngestionPort}: adapters need to read and advance their
 * own progress, but must never be handed the port that writes master data. Both are implemented by
 * the same repository, so there is still exactly one class writing to the sync-owned schema.
 * <p>
 * A watermark must only ever be advanced through {@link SourceAdapter#commitProgress()}, after the
 * ingestion has confirmed the run committed — advancing it during the crawl would skip records that
 * were fetched but never stored. See ADR 0006.
 */
public interface SyncStateStore {
    /**
     * @param scope the subdivision the adapter crawls independently, e.g. an ISO country code
     * @return when that scope was last fully ingested, empty if never
     */
    Optional<Instant> watermark(String source, String scope);

    void recordWatermark(String source, String scope, Instant watermark);
}
