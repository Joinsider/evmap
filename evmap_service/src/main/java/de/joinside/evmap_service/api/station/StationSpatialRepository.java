package de.joinside.evmap_service.api.station;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PostgreSQL/PostGIS-only query; Spring Data method derivation cannot express ST_DWithin. */
@Repository
class StationSpatialRepository {
    private static final String COLUMNS = "s.id, s.display_name, s.street, s.city, s.postal_code, s.country_code, s.operator_name, s.latitude, s.longitude, s.availability_status";
    private final JdbcClient jdbc;
    StationSpatialRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * Stations within {@code radiusKm} of the point, at most {@code limit} of them.
     *
     * <p>{@code max_power_kw} is the strongest connector <em>the station has</em> — the lateral
     * aggregate sits outside the filter join on purpose, so the value a pin is coloured by does not
     * change when the user narrows the connector type. It is also the ranking key: a viewport wide
     * enough to overflow {@code limit} keeps the fastest chargers rather than an arbitrary slice,
     * so zooming out degrades to the high-power backbone instead of to noise.
     *
     * <p>{@code excludeOperators} is applied here rather than left to the client because the row
     * limit is applied here: filtering after the fact would let hidden operators consume slots in
     * the result and silently shrink the map to fewer stations than the user asked to see. A
     * station with no operator name is never excluded — "unknown" is not a network anyone chose to
     * hide.
     *
     * <p>{@code includeOperators} is the same argument from the other end: the allowlist the app
     * sends when the user hid every network except a few. It is applied in the query for the same
     * reason, and a station with no operator name <em>is</em> left out by it — with an allowlist the
     * question is "did the user pick this network", and an unnamed one was never picked.
     */
    List<StationController.StationSummary> findNearby(NearbyQuery nearby) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + ", p.max_power_kw FROM master.charging_station s LEFT JOIN LATERAL (SELECT MAX(power_kw) AS max_power_kw FROM master.charging_connector WHERE station_id = s.id) p ON true LEFT JOIN master.charging_connector c ON c.station_id = s.id WHERE ST_DWithin(s.location, ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography, :radius)");
        if (nearby.connectorTypes() != null) sql.append(" AND lower(c.connector_type) IN (:connectorTypes)");
        if (nearby.minPowerKw() != null) sql.append(" AND c.power_kw >= :minPowerKw");
        if (nearby.operator() != null) sql.append(" AND lower(s.operator_name) = lower(:operator)");
        if (nearby.excludeOperators() != null) sql.append(" AND (s.operator_name IS NULL OR lower(s.operator_name) NOT IN (:excludeOperators))");
        if (nearby.includeOperators() != null) sql.append(" AND lower(s.operator_name) IN (:includeOperators)");
        // s.id is the primary key, so the remaining selected columns are functionally dependent on it.
        sql.append(" GROUP BY s.id, p.max_power_kw ORDER BY p.max_power_kw DESC NULLS LAST, s.id LIMIT :limit");
        var query = jdbc.sql(sql.toString()).param("latitude", nearby.latitude()).param("longitude", nearby.longitude())
                .param("radius", nearby.radiusKm() * 1000.0).param("limit", nearby.limit());
        if (nearby.connectorTypes() != null) query.param("connectorTypes", lowerCase(nearby.connectorTypes()));
        if (nearby.minPowerKw() != null) query.param("minPowerKw", nearby.minPowerKw());
        if (nearby.operator() != null) query.param("operator", nearby.operator());
        if (nearby.excludeOperators() != null) query.param("excludeOperators", lowerCase(nearby.excludeOperators()));
        if (nearby.includeOperators() != null) query.param("includeOperators", lowerCase(nearby.includeOperators()));
        return query.query((rs, row) -> new StationController.StationSummary(UUID.fromString(rs.getString("id")), rs.getString("display_name"), rs.getString("street"), rs.getString("city"), rs.getString("postal_code"), rs.getString("country_code"), rs.getString("operator_name"), rs.getDouble("latitude"), rs.getDouble("longitude"), rs.getString("availability_status"), rs.getBigDecimal("max_power_kw"))).list();
    }

    private static List<String> lowerCase(List<String> values) {
        return values.stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
    }
}
