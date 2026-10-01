package de.joinside.evmap_service.pricing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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
    private final JdbcClient jdbc;

    ChargePointInventory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record StationLocation(UUID id, double latitude, double longitude, String countryCode, String operatorName) {
    }

    /**
     * @param operatorName the charge point's own operator, {@code null} when it is the station's
     * @param source       the source token that supplied the charge point, which also supplied its price
     * @param price        the register's price, {@code null} when it publishes none
     */
    record KnownChargePoint(UUID id, String source, String evseId, String evseIdNormalized, String operatorName,
                            List<StationPrices.Connector> connectors, AdHocPrice price) {
    }

    Optional<StationLocation> location(UUID stationId) {
        return jdbc.sql("SELECT id, latitude, longitude, country_code, operator_name FROM master.charging_station "
                        + "WHERE id=:id")
                .param("id", stationId)
                .query((rs, row) -> new StationLocation(rs.getObject("id", UUID.class), rs.getDouble("latitude"),
                        rs.getDouble("longitude"), rs.getString("country_code"), rs.getString("operator_name")))
                .optional();
    }

    /** Every charge point of a station with its connectors and register price, in a stable order. */
    List<KnownChargePoint> forStation(UUID stationId) {
        Map<UUID, KnownChargePoint> chargePoints = new LinkedHashMap<>();
        Map<UUID, List<StationPrices.Connector>> connectors = new LinkedHashMap<>();
        jdbc.sql("SELECT cp.id, cp.source, cp.evse_id, cp.evse_id_normalized, cp.operator_name, "
                        + "p.currency, p.energy_per_kwh, p.session_fee, p.time_fee_per_minute, p.free, p.further_fees, "
                        + "p.observed_at "
                        + "FROM master.charge_point cp LEFT JOIN master.charge_point_price p ON p.charge_point_id = cp.id "
                        + "WHERE cp.station_id=:stationId ORDER BY cp.source_charge_point_id")
                .param("stationId", stationId)
                .query(rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    List<StationPrices.Connector> plugs = new ArrayList<>();
                    connectors.put(id, plugs);
                    chargePoints.put(id, new KnownChargePoint(id, rs.getString("source"), rs.getString("evse_id"),
                            rs.getString("evse_id_normalized"), rs.getString("operator_name"), plugs, price(rs)));
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
        return List.copyOf(chargePoints.values());
    }

    private static AdHocPrice price(ResultSet rs) throws SQLException {
        String currency = rs.getString("currency");
        if (currency == null) return null;
        BigDecimal time = rs.getBigDecimal("time_fee_per_minute");
        Timestamp observed = rs.getTimestamp("observed_at");
        return new AdHocPrice(currency.trim(), strip(rs.getBigDecimal("energy_per_kwh")),
                strip(rs.getBigDecimal("session_fee")),
                time == null ? List.of() : List.of(new AdHocPrice.TimeFee(0, strip(time))),
                rs.getBoolean("free"), rs.getBoolean("further_fees"), observed == null ? null : observed.toInstant());
    }

    /** {@code NUMERIC(8,4)} reads back as 0.3710; the API should say 0.371. */
    private static BigDecimal strip(BigDecimal value) {
        if (value == null) return null;
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
