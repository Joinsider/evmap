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
     * @param operatorName        the operator of this charge point, where the source knows it per charge
     *                            point; {@code null} means the station's operator. Matters where a station
     *                            bundles several operators' sites (ADR 0012, "Spain (L4)"; ADR 0022).
     * @param price               the ad-hoc price the source publishes for it, or {@code null} when it
     *                            publishes none or none the adapter is certain about.
     */
    public record SourceChargePoint(String sourceChargePointId, String evseId, List<SourceConnector> connectors,
                                    String operatorName, SourcePrice price) {
        public SourceChargePoint {
            connectors = connectors == null ? List.of() : List.copyOf(connectors);
        }

        /** A charge point whose operator is the station's and whose price is not published. */
        public SourceChargePoint(String sourceChargePointId, String evseId, List<SourceConnector> connectors) {
            this(sourceChargePointId, evseId, connectors, null, null);
        }
    }

    /**
     * An ad-hoc price, already normalized: gross (VAT included), in {@code currency}. Every amount is
     * optional, and an absent one means "not published", never "free" — that is {@code free}.
     *
     * @param energyPerKwh     price per kWh
     * @param sessionFee       fee per charging session
     * @param timeFeePerMinute fee per minute of charging, from the first minute
     * @param free             charging costs nothing; never combined with an amount
     * @param furtherFees      the source lists further fees (idle fees, time windows) that are not
     *                         carried here, so the client must not present the amounts as complete
     * @param observedAt       when the source last stated this price, {@code null} when unknown
     */
    public record SourcePrice(String currency, BigDecimal energyPerKwh, BigDecimal sessionFee,
                              BigDecimal timeFeePerMinute, boolean free, boolean furtherFees, Instant observedAt) {

        public static SourcePrice freeOfCharge(Instant observedAt) {
            return new SourcePrice("EUR", null, null, null, true, false, observedAt);
        }
    }
}
