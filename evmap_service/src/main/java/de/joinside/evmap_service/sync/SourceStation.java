package de.joinside.evmap_service.sync;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

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
 * @param links              records of other sources that describe the same station, as the source itself
 *                           states — the Mobilithek's feeds name the register's station id. The ingestion
 *                           resolves a new record through them before it falls back to position (ADR 0025).
 * @param namesStation       whether this source's display name and operator name replace those of a station
 *                           another source already named. {@code false} for a source whose labels are worse than
 *                           the register's — the Mobilithek's are often codes ("000501") or brands ("ENBW"), and
 *                           the operator name is what every user's provider preferences are keyed by (ADR 0014).
 *                           A station the source creates or names alone still gets its labels.
 */
public record SourceStation(String source, String sourceStationId, String name, String street, String city,
                            String postalCode, String countryCode, String operatorName, double latitude,
                            double longitude, String availabilityStatus,
                            Instant lastUpdatedAt, List<SourceConnector> connectors,
                            List<SourceChargePoint> chargePoints, List<SourceLink> links, boolean namesStation) {

    public SourceStation {
        connectors = connectors == null ? List.of() : List.copyOf(connectors);
        chargePoints = chargePoints == null ? List.of() : List.copyOf(chargePoints);
        links = links == null ? List.of() : List.copyOf(links);
    }

    /** A record that states no links and names its stations — every source but the Mobilithek. */
    public SourceStation(String source, String sourceStationId, String name, String street, String city,
                         String postalCode, String countryCode, String operatorName, double latitude,
                         double longitude, String availabilityStatus,
                         Instant lastUpdatedAt, List<SourceConnector> connectors,
                         List<SourceChargePoint> chargePoints) {
        this(source, sourceStationId, name, street, city, postalCode, countryCode, operatorName,
                latitude, longitude, availabilityStatus, lastUpdatedAt, connectors, chargePoints, List.of(), true);
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
     * Another source's record of the same station, in that source's terms.
     *
     * @param source          the other source's token, e.g. {@code BNetzA}
     * @param sourceStationId that source's id for the station
     */
    public record SourceLink(String source, String sourceStationId) {
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
     * @param prices              the ad-hoc prices the source publishes for it, usually one; several where they
     *                            differ by payment means (ADR 0022, L6p). Empty when it publishes none or none
     *                            the adapter is certain about.
     */
    public record SourceChargePoint(String sourceChargePointId, String evseId, List<SourceConnector> connectors,
                                    String operatorName, List<SourcePrice> prices) {
        public SourceChargePoint {
            connectors = connectors == null ? List.of() : List.copyOf(connectors);
            prices = prices == null ? List.of() : prices.stream().filter(Objects::nonNull).toList();
        }

        /** A charge point with at most one price. */
        public SourceChargePoint(String sourceChargePointId, String evseId, List<SourceConnector> connectors,
                                 String operatorName, SourcePrice price) {
            this(sourceChargePointId, evseId, connectors, operatorName, price == null ? List.of() : List.of(price));
        }

        /** A charge point whose operator is the station's and whose price is not published. */
        public SourceChargePoint(String sourceChargePointId, String evseId, List<SourceConnector> connectors) {
            this(sourceChargePointId, evseId, connectors, null, List.of());
        }
    }

    /**
     * An ad-hoc price, already normalized: gross (VAT included), in {@code currency}. Every amount is
     * optional, and an absent one means "not published", never "free" — that is {@code free}.
     *
     * @param energyPerKwh   price per kWh, when it is the same at every hour
     * @param energyWindows  prices per kWh by time of day or weekday, when they differ (then {@code energyPerKwh} is
     *                       {@code null}); every moment of the week falls into one of them as the source states
     * @param sessionFee     fee per charging session
     * @param timeFees       fees per minute of charging, each from a minute of the session on, in that order
     * @param free           charging costs nothing; never combined with an amount
     * @param furtherFees    the source lists further fees (idle fees after charging) that are not carried here, so
     *                       the client must not present the amounts as complete
     * @param observedAt     when the source last stated this price, {@code null} when unknown
     * @param paymentMeans   how this price is paid, as DATEX II names it ({@code qrCode}, {@code emv}, …); only
     *                       relevant where a charge point has several prices, empty when the source says nothing
     * @param vatBasisStated the source stated per amount whether VAT is included (DATEX II {@code taxIncluded}),
     *                       rather than the basis being inferred — such a price wins over a live tariff whose basis
     *                       was inferred (ADR 0022, L6p)
     * @param statedBy       who published it, credited next to the price ("EnBW … via Mobilithek"); {@code null}
     *                       credits the source token
     */
    public record SourcePrice(String currency, BigDecimal energyPerKwh, List<EnergyWindow> energyWindows,
                              BigDecimal sessionFee, List<TimeFee> timeFees, boolean free, boolean furtherFees,
                              Instant observedAt, List<String> paymentMeans, boolean vatBasisStated, String statedBy) {

        public SourcePrice {
            energyWindows = energyWindows == null ? List.of() : List.copyOf(energyWindows);
            timeFees = timeFees == null ? List.of() : List.copyOf(timeFees);
            paymentMeans = paymentMeans == null ? List.of() : List.copyOf(paymentMeans);
        }

        /** A register's price: one energy price, one fee per minute from the start, basis not stated. */
        public SourcePrice(String currency, BigDecimal energyPerKwh, BigDecimal sessionFee,
                           BigDecimal timeFeePerMinute, boolean free, boolean furtherFees, Instant observedAt) {
            this(currency, energyPerKwh, List.of(), sessionFee,
                    timeFeePerMinute == null ? List.of() : List.of(new TimeFee(0, null, timeFeePerMinute, null, null)),
                    free, furtherFees, observedAt, List.of(), false, null);
        }

        public static SourcePrice freeOfCharge(Instant observedAt) {
            return new SourcePrice("EUR", null, null, null, true, false, observedAt);
        }
    }

    /**
     * A fee per minute of charging.
     *
     * @param fromMinute the minute of the session from which it applies, 0 for the whole session
     * @param toMinute   the minute from which it no longer applies, {@code null} for the rest of the session
     * @param perMinute  the gross fee, or {@code null} when the source has one whose amount is not certain
     * @param cap        the most this fee comes to in one session, {@code null} when uncapped
     * @param window     when it applies, {@code null} for always
     */
    public record TimeFee(int fromMinute, Integer toMinute, BigDecimal perMinute, BigDecimal cap, TimeWindow window) {
    }

    /** An energy price that applies only within {@code window}. */
    public record EnergyWindow(BigDecimal perKwh, TimeWindow window) {
    }

    /**
     * A recurring period of the week, in local time as the source writes it (ADR 0022, L6p: the offsets the feeds
     * append contradict their own texts).
     *
     * @param from start of the period, {@code HH:mm}
     * @param to   end of the period, {@code HH:mm}, {@code 24:00} for midnight at its end; before {@code from} when it
     *             runs past midnight
     * @param days the weekdays it applies on ({@code monday} … {@code sunday}), empty for every day
     */
    public record TimeWindow(String from, String to, List<String> days) {
        public TimeWindow {
            days = days == null ? List.of() : List.copyOf(days);
        }
    }
}
