package de.joinside.evmap_service.sync;

import java.util.UUID;
import java.util.stream.Stream;

/**
 * The only write boundary for sync-owned master data.
 */
public interface StationIngestionPort {
    /**
     * Ingests a run's worth of stations. Commits in batches rather than as one transaction, so a run
     * that dies partway leaves the work it already did — see ADR 0007.
     */
    IngestionResult upsert(Stream<SourceStation> stations);

    /** Records the start of a run and returns its id. */
    UUID startRun();

    /**
     * Closes a run. Runs the write in its own transaction, so a run that failed mid-ingestion is
     * still recorded after the ingestion transaction rolled back.
     */
    void finishRun(UUID runId, RunStatus status, IngestionResult result, String errorMessage);

    enum RunStatus {
        SUCCEEDED,
        /** Finished, but individual records could not be ingested. Their count is in the result. */
        PARTIAL,
        FAILED,
        SKIPPED
    }

    /**
     * What a single ingestion run did. Returned rather than logged inside the repository so the caller
     * decides how a run is reported.
     *
     * @param failed records that could not be ingested and were skipped; the rest of the run committed
     */
    record IngestionResult(int processed, int created, int updated, int unchanged, int failed) {
        /** A run that ingested nothing — for the skipped and failed paths, which have no counts. */
        public static IngestionResult none() {
            return new IngestionResult(0, 0, 0, 0, 0);
        }
    }
}
