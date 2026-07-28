package de.joinside.evmap_service.sync;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Normalized upstream record; source adapters must not leak into API code.
 *
 * @param availabilityStatus reported service state, one of {@link AvailabilityStatus}; {@code null}
 *                           when the source says nothing about it
 */
public record SourceStation(String source, String sourceStationId, String name, String street, String city,
                            String postalCode, String countryCode, String operatorName, double latitude,
                            double longitude, String availabilityStatus,
                            Instant lastUpdatedAt, List<SourceConnector> connectors) {
    public record SourceConnector(String connectorType, BigDecimal powerKw, int quantity) {
    }
}
