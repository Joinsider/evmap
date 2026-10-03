package de.joinside.evmap_service.pricing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read access to the charge point inventory and the register prices {@code sync} writes. Writes nothing —
 * like {@code availability.ChargePointDirectory}, its own type so the dependency stays visible.
 */
@Repository
@ConditionalOnProperty(name = "evmap.pricing.enabled", havingValue = "true", matchIfMissing = true)
class ChargePointInventory {
    private static final Logger log = LoggerFactory.getLogger(ChargePointInventory.class);
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private static final TypeReference<List<AdHocPrice.TimeFee>> TIME_FEES = new TypeReference<>() {
    };
    private static final TypeReference<List<AdHocPrice.EnergyWindow>> ENERGY_WINDOWS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;

    ChargePointInventory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record StationLocation(UUID id, double latitude, double longitude, String countryCode, String operatorName) {
    }

    /**
     * @param operatorName the charge point's own operator, {@code null} when it is the station's
     * @param source       the source token that supplied the charge point, which also supplied its prices
     * @param prices       the register's prices in their order, empty when it publishes none
     */
    record KnownChargePoint(UUID id, String source, String evseId, String evseIdNormalized, String operatorName,
                            List<StationPrices.Connector> connectors, List<StoredPrice> prices) {
    }

    /**
     * A price as {@code sync} stored it.
     *
     * @param vatBasisStated the source stated whether VAT is included, rather than the basis being inferred
     * @param statedBy       who published it, to credit; {@code null} credits the source token
     */
    record StoredPrice(AdHocPrice price, boolean vatBasisStated, String statedBy) {
    }

    Optional<StationLocation> location(UUID stationId) {
        return jdbc.sql("SELECT id, latitude, longitude, country_code, operator_name FROM master.charging_station "
                        + "WHERE id=:id")
                .param("id", stationId)
                .query((rs, row) -> new StationLocation(rs.getObject("id", UUID.class), rs.getDouble("latitude"),
                        rs.getDouble("longitude"), rs.getString("country_code"), rs.getString("operator_name")))
                .optional();
    }

    /** Every charge point of a station with its connectors and register prices, in a stable order. */
    List<KnownChargePoint> forStation(UUID stationId) {
        Map<UUID, KnownChargePoint> chargePoints = new LinkedHashMap<>();
        Map<UUID, List<StationPrices.Connector>> connectors = new LinkedHashMap<>();
        jdbc.sql("SELECT cp.id, cp.source, cp.evse_id, cp.evse_id_normalized, cp.operator_name, "
                        + "p.currency, p.energy_per_kwh, p.energy_windows, p.session_fee, p.time_fees, p.free, "
                        + "p.further_fees, p.observed_at, p.payment_means, p.vat_basis_stated, p.stated_by "
                        + "FROM master.charge_point cp LEFT JOIN master.charge_point_price p ON p.charge_point_id = cp.id "
                        + "WHERE cp.station_id=:stationId ORDER BY cp.source_charge_point_id, p.ordinal")
                .param("stationId", stationId)
                .query(rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    KnownChargePoint known = chargePoints.get(id);
                    if (known == null) {
                        List<StationPrices.Connector> plugs = new ArrayList<>();
                        connectors.put(id, plugs);
                        known = new KnownChargePoint(id, rs.getString("source"), rs.getString("evse_id"),
                                rs.getString("evse_id_normalized"), rs.getString("operator_name"), plugs,
                                new ArrayList<>());
                        chargePoints.put(id, known);
                    }
                    StoredPrice price = price(rs);
                    if (price != null) known.prices().add(price);
                });
        if (chargePoints.isEmpty()) return List.of();

        jdbc.sql("SELECT charge_point_id, connector_type, power_kw, quantity FROM master.charging_connector "
                        + "WHERE station_id=:stationId AND charge_point_id IS NOT NULL ORDER BY connector_type, power_kw")
                .param("stationId", stationId)
                .query(rs -> {
                    List<StationPrices.Connector> plugs = connectors.get(rs.getObject("charge_point_id", UUID.class));
                    if (plugs != null)
                        plugs.add(new StationPrices.Connector(rs.getString("connector_type"), rs.getBigDecimal("power_kw"),
                                rs.getInt("quantity")));
                });
        return chargePoints.values().stream()
                .map(known -> new KnownChargePoint(known.id(), known.source(), known.evseId(), known.evseIdNormalized(),
                        known.operatorName(), known.connectors(), List.copyOf(known.prices())))
                .toList();
    }

    private static StoredPrice price(ResultSet rs) throws SQLException {
        String currency = rs.getString("currency");
        if (currency == null) return null;
        Timestamp observed = rs.getTimestamp("observed_at");
        Array means = rs.getArray("payment_means");
        AdHocPrice price = new AdHocPrice(currency.trim(), strip(rs.getBigDecimal("energy_per_kwh")),
                read(rs.getString("energy_windows"), ENERGY_WINDOWS).stream()
                        .map(window -> new AdHocPrice.EnergyWindow(strip(window.perKwh()), window.window()))
                        .toList(),
                strip(rs.getBigDecimal("session_fee")),
                read(rs.getString("time_fees"), TIME_FEES).stream()
                        .map(fee -> new AdHocPrice.TimeFee(fee.fromMinute(), fee.toMinute(), strip(fee.perMinute()), strip(fee.cap()),
                                fee.window()))
                        .toList(),
                rs.getBoolean("free"), rs.getBoolean("further_fees"), observed == null ? null : observed.toInstant(),
                means == null ? List.of() : List.of((String[]) means.getArray()));
        return new StoredPrice(price, rs.getBoolean("vat_basis_stated"), rs.getString("stated_by"));
    }

    /** The JSON columns of migration 013; a value that does not read is no list, never a failed station screen. */
    private static <T> List<T> read(String json, TypeReference<List<T>> type) {
        if (json == null) return List.of();
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.warn("Stored price detail could not be read — showing the price without it", e);
            return List.of();
        }
    }

    /** {@code NUMERIC(8,4)} reads back as 0.3710; the API should say 0.371. */
    private static BigDecimal strip(BigDecimal value) {
        if (value == null) return null;
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
