package de.joinside.evmap_service.sync;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Normalized upstream record; source adapters must not leak into API code.
 *
 * @param availabilityStatus reported service state, one of {@link AvailabilityStatus}; {@code null}
 *                           when the source says nothing about it
 * @param connectors         station-level connector totals. Sources that describe individual charge
 *                           points leave this empty and fill {@code chargePoints} instead; the
 *                           ingestion derives the station's totals from them.
 * @param chargePoints       individual charge points, for the sources that identify them — BNetzA in
 *                           its {@code Steckertypen{n}} column groups, IRVE in its one row per
 *                           {@code id_pdc_itinerance}. Empty for sources that report only totals,
 *                           which is why {@code master.charging_connector.charge_point_id} is
 *                           nullable. See ADR 0015.
 */
public record SourceStation(String source, String sourceStationId, String name, String street, String city,
                            String postalCode, String countryCode, String operatorName, double latitude,
                            double longitude, String availabilityStatus,
                            Instant lastUpdatedAt, List<SourceConnector> connectors,
                            List<SourceChargePoint> chargePoints) {

    public SourceStation {
        connectors = connectors == null ? List.of() : List.copyOf(connectors);
        chargePoints = chargePoints == null ? List.of() : List.copyOf(chargePoints);
    }

    /**
     * A source that reports only station-level totals — Open Charge Map, whose {@code Connection}
     * carries a {@code Quantity} and no identifier of its own.
     */
    public SourceStation(String source, String sourceStationId, String name, String street, String city,
                         String postalCode, String countryCode, String operatorName, double latitude,
                         double longitude, String availabilityStatus,
                         Instant lastUpdatedAt, List<SourceConnector> connectors) {
        this(source, sourceStationId, name, street, city, postalCode, countryCode, operatorName,
                latitude, longitude, availabilityStatus, lastUpdatedAt, connectors, List.of());
    }

    public record SourceConnector(String connectorType, BigDecimal powerKw, int quantity) {
    }

    /**
     * One charge point of a station.
     *
     * @param sourceChargePointId identifier within the source, unique per {@code (source, id)} — the
     *                            register's own key where it has one, otherwise the station id and
     *                            the charge point's position. Stable across runs, because it is what
     *                            re-ingestion matches on.
     * @param evseId              the published eMI3 / ISO 15118 EVSE-ID, or {@code null} when the
     *                            source does not carry one. This is the join key for live
     *                            availability; without it the charge point simply has no live status.
     * @param connectors          the plugs on this charge point.
     */
    public record SourceChargePoint(String sourceChargePointId, String evseId, List<SourceConnector> connectors) {
        public SourceChargePoint {
            connectors = connectors == null ? List.of() : List.copyOf(connectors);
        }
    }
}
