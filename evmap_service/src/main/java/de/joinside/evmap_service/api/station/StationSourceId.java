package de.joinside.evmap_service.api.station;

import java.io.Serializable;

public record StationSourceId(String source, String sourceStationId) implements Serializable { }
