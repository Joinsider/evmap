package de.joinside.evmap_service.sync;

import java.util.stream.Stream;

public interface SourceAdapter {
    Stream<SourceStation> fetchStations();
}
