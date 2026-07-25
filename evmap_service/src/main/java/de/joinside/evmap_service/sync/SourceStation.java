package de.joinside.evmap_service.sync;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Normalized upstream record; source adapters must not leak into API code.
 */
public record SourceStation(String source, String sourceStationId, String name, String street, String city,
                            String postalCode, String countryCode, String operatorName, double latitude,
                            double longitude,
                            Instant lastUpdatedAt, List<SourceConnector> connectors) {
    public record SourceConnector(String connectorType, BigDecimal powerKw, int quantity) {
    }
}
