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

    /**
     * Marks the stations {@code source} has made redundant, and unmarks the ones it no longer does (ADR 0025).
     * <p>
     * A station {@code source} does not maintain is superseded by one it does when all of its EVSE-IDs sit on
     * {@code source}'s stations — then by the one holding most of them — or when it has no EVSE-ID and lies within
     * 30 m of one, in the same country — then by the nearest. A station with an EVSE-ID {@code source} does not know
     * is a different charge point and stays. Only countries where {@code source} maintains stations are looked at;
     * one superseding source per country.
     *
     * @return how many stations are superseded by {@code source} after the call
     */
    int supersedeDuplicates(String source);

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
