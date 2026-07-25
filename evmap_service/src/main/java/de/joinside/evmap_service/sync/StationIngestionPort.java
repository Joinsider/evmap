package de.joinside.evmap_service.sync;

import java.util.stream.Stream;

/**
 * The only write boundary for sync-owned master data.
 */
public interface StationIngestionPort {
    void upsert(Stream<SourceStation> stations);
}
