package de.joinside.evmap_service.availability;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Read access to the charge point inventory {@code sync} writes.
 * <p>
 * This is the whole of the contact surface between the two axes: {@code availability} reads
 * {@code master.charge_point} and {@code master.charging_station}, and writes neither. Kept as its
 * own type rather than reusing the {@code api} repositories so that the dependency is visible and
 * one-directional — {@code api} may read availability, availability never reads {@code api}.
 */
@Repository
@ConditionalOnProperty(name = "evmap.availability.enabled", havingValue = "true", matchIfMissing = true)
class ChargePointDirectory implements StoredChargePoints {
    private static final String EVSE_ID_NORMALIZED = "evse_id_normalized";

    private final JdbcClient jdbc;

    ChargePointDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Where a station is, and which country's providers therefore apply to it. */
    record StationLocation(UUID id, double latitude, double longitude, String countryCode) {
    }

    /**
     * @param evseId           as published, for display
     * @param evseIdNormalized the comparison form, {@code null} when the source published no EVSE-ID.
     *                         Such a charge point is counted but can never be resolved.
     */
    record KnownChargePoint(UUID chargePointId, UUID stationId, String evseId, String evseIdNormalized) {
    }

    Optional<StationLocation> location(UUID stationId) {
        return jdbc.sql("SELECT id, latitude, longitude, country_code FROM master.charging_station WHERE id=:id")
                .param("id", stationId)
                .query((rs, row) -> new StationLocation(rs.getObject("id", UUID.class),
                        rs.getDouble("latitude"), rs.getDouble("longitude"), rs.getString("country_code")))
                .optional();
    }

    /** Every charge point of one station, including those without an EVSE-ID so the counts add up. */
    List<KnownChargePoint> forStation(UUID stationId) {
        return jdbc.sql("SELECT id, station_id, evse_id, evse_id_normalized FROM master.charge_point " +
                        "WHERE station_id=:stationId ORDER BY source_charge_point_id")
                .param("stationId", stationId)
                .query((rs, row) -> new KnownChargePoint(rs.getObject("id", UUID.class),
                        rs.getObject("station_id", UUID.class),
                        rs.getString("evse_id"), rs.getString(EVSE_ID_NORMALIZED)))
                .list();
    }

    /**
     * Charge points inside a viewport that can actually be resolved.
     * <p>
     * Unlike {@link #forStation(UUID)} this filters out the ones without an EVSE-ID: the map only
     * shows stations that have a live answer, so a charge point that can never match is weight the
     * query does not need to carry. {@code limit} bounds a zoomed-out viewport, which would otherwise
     * select every charge point in the country.
     */
    List<KnownChargePoint> inBounds(GeoBounds bounds, int limit) {
        return jdbc.sql("SELECT cp.id, cp.station_id, cp.evse_id, cp.evse_id_normalized " +
                        "FROM master.charge_point cp " +
                        "JOIN master.charging_station s ON s.id = cp.station_id " +
                        "WHERE cp.evse_id_normalized IS NOT NULL " +
                        "AND s.latitude BETWEEN :latMin AND :latMax " +
                        "AND s.longitude BETWEEN :lonMin AND :lonMax " +
                        "LIMIT :limit")
                .param("latMin", bounds.latMin())
                .param("latMax", bounds.latMax())
                .param("lonMin", bounds.lonMin())
                .param("lonMax", bounds.lonMax())
                .param("limit", limit)
                .query((rs, row) -> new KnownChargePoint(rs.getObject("id", UUID.class),
                        rs.getObject("station_id", UUID.class),
                        rs.getString("evse_id"), rs.getString(EVSE_ID_NORMALIZED)))
                .list();
    }

    /**
     * Every charge point of whole countries — about 200.000 rows for Germany, which is why only a diagnostic that
     * runs once an hour asks for it.
     */
    @Override
    public Inventory inCountries(Collection<String> countryCodes) {
        if (countryCodes.isEmpty()) return new Inventory(0, Set.of());
        long[] chargePoints = {0};
        Set<String> evseIds = new HashSet<>();
        jdbc.sql("SELECT cp.evse_id_normalized FROM master.charge_point cp " +
                        "JOIN master.charging_station s ON s.id = cp.station_id " +
                        "WHERE s.country_code IN (:countries)")
                .param("countries", countryCodes)
                .query(rs -> {
                    chargePoints[0]++;
                    String evseId = rs.getString(EVSE_ID_NORMALIZED);
                    if (evseId != null) evseIds.add(evseId);
                });
        return new Inventory(chargePoints[0], evseIds);
    }

    /** The distinct countries a viewport touches, so only the relevant providers are consulted. */
    List<String> countriesInBounds(GeoBounds bounds) {
        return jdbc.sql("SELECT DISTINCT country_code FROM master.charging_station " +
                        "WHERE latitude BETWEEN :latMin AND :latMax " +
                        "AND longitude BETWEEN :lonMin AND :lonMax")
                .param("latMin", bounds.latMin())
                .param("latMax", bounds.latMax())
                .param("lonMin", bounds.lonMin())
                .param("lonMax", bounds.lonMax())
                .query(String.class)
                .list();
    }
}
