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
     * A station another source describes better — a register entry next to the Mobilithek station that replaced it
     * (ADR 0025). It stays reachable by id, for favorites and comments, but is not offered on the map or the route.
     */
    static final String NOT_SUPERSEDED = "s.superseded_by IS NULL";

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
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + ", p.max_power_kw FROM master.charging_station s LEFT JOIN LATERAL (SELECT MAX(power_kw) AS max_power_kw FROM master.charging_connector WHERE station_id = s.id) p ON true LEFT JOIN master.charging_connector c ON c.station_id = s.id WHERE ST_DWithin(s.location, ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography, :radius)"
                + " AND " + NOT_SUPERSEDED);
        appendFilters(sql, nearby.connectorTypes(), nearby.minPowerKw(), nearby.operator(), nearby.excludeOperators(), nearby.includeOperators());
        // s.id is the primary key, so the remaining selected columns are functionally dependent on it.
        sql.append(" GROUP BY s.id, p.max_power_kw ORDER BY p.max_power_kw DESC NULLS LAST, s.id LIMIT :limit");
        var query = jdbc.sql(sql.toString()).param("latitude", nearby.latitude()).param("longitude", nearby.longitude())
                .param("radius", nearby.radiusKm() * 1000.0).param("limit", nearby.limit());
        bindFilters(query, nearby.connectorTypes(), nearby.minPowerKw(), nearby.operator(), nearby.excludeOperators(), nearby.includeOperators());
        return query.query((rs, row) -> summary(rs)).list();
    }

    /**
     * Stations within {@code corridorKm} of the route, in driving order, at most {@code limit} of them.
     *
     * <p>The corridor test runs against {@code ST_Subdivide}d pieces of the line, not the whole of it:
     * a route from Hamburg to Munich has a bounding box that covers most of Germany, and the GiST index
     * on {@code location} would hand back every station in it only for the distance check to throw most
     * away. Each piece has a small box, so the index answers with what is actually near the road. The
     * inner query keeps the strongest {@code limit} chargers when the corridor holds more (as
     * {@link #findNearby} does for a viewport) and the outer one puts them in driving order, which is
     * what the client lists them by.
     *
     * <p>{@code along} is the station's position projected onto the line, in kilometres from the start.
     * It is the position of the nearest point of the route, so on a route that doubles back it can name
     * the earlier of two passes; for a polyline of a few hundred points that is the right trade for a
     * query that has no notion of the driver's progress.
     */
    List<StationController.RouteStation> findAlongRoute(AlongRouteQuery along) {
        StringBuilder sql = new StringBuilder("SELECT ranked.* FROM (SELECT " + COLUMNS + ", p.max_power_kw, "
                + "ST_LineLocatePoint(route.line, s.location::geometry) * ST_Length(route.line::geography) / 1000.0 AS along_km, "
                + "ST_Distance(s.location, route.line::geography) / 1000.0 AS off_km "
                + "FROM route CROSS JOIN master.charging_station s "
                + "LEFT JOIN LATERAL (SELECT MAX(power_kw) AS max_power_kw FROM master.charging_connector WHERE station_id = s.id) p ON true "
                + "LEFT JOIN master.charging_connector c ON c.station_id = s.id "
                + "WHERE s.id IN (SELECT n.id FROM parts JOIN master.charging_station n ON ST_DWithin(n.location, parts.part, :corridor)) "
                + "AND " + NOT_SUPERSEDED);
        appendFilters(sql, along.connectorTypes(), along.minPowerKw(), null, along.excludeOperators(), along.includeOperators());
        sql.append(" GROUP BY s.id, p.max_power_kw, route.line ORDER BY p.max_power_kw DESC NULLS LAST, s.id LIMIT :limit) ranked ORDER BY ranked.along_km, ranked.id");
        var query = jdbc.sql("WITH route AS (SELECT ST_GeomFromText(:wkt, 4326) AS line), "
                        + "parts AS (SELECT d.g::geography AS part FROM route, LATERAL ST_Subdivide(route.line, 64) AS d(g)) " + sql)
                .param("wkt", lineString(along.route())).param("corridor", along.corridorKm() * 1000.0).param("limit", along.limit());
        bindFilters(query, along.connectorTypes(), along.minPowerKw(), null, along.excludeOperators(), along.includeOperators());
        return query.query((rs, row) -> new StationController.RouteStation(summary(rs), rs.getDouble("along_km"), rs.getDouble("off_km"))).list();
    }

    /** WKT is longitude first. The numbers are formatted here from doubles, and still go in as a bound parameter. */
    private static String lineString(List<StationController.RoutePoint> route) {
        StringBuilder wkt = new StringBuilder("LINESTRING(");
        for (int i = 0; i < route.size(); i++) {
            if (i > 0) wkt.append(", ");
            wkt.append(route.get(i).longitude()).append(' ').append(route.get(i).latitude());
        }
        return wkt.append(')').toString();
    }

    private static StationController.StationSummary summary(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StationController.StationSummary(UUID.fromString(rs.getString("id")), rs.getString("display_name"), rs.getString("street"), rs.getString("city"), rs.getString("postal_code"), rs.getString("country_code"), rs.getString("operator_name"), rs.getDouble("latitude"), rs.getDouble("longitude"), rs.getString("availability_status"), rs.getBigDecimal("max_power_kw"));
    }

    private static void appendFilters(StringBuilder sql, List<String> connectorTypes, java.math.BigDecimal minPowerKw, String operator, List<String> excludeOperators, List<String> includeOperators) {
        if (connectorTypes != null) sql.append(" AND lower(c.connector_type) IN (:connectorTypes)");
        if (minPowerKw != null) sql.append(" AND c.power_kw >= :minPowerKw");
        // A station bundled from several operators' sites (Spain, Switzerland) has the majority operator on the
        // station and the others on their charge points (ADR 0022): it matches an operator if any of them does,
        // and is hidden only when every one of them is hidden.
        if (operator != null) sql.append(" AND (lower(s.operator_name) = lower(:operator) OR EXISTS (SELECT 1 FROM master.charge_point cp "
                + "WHERE cp.station_id = s.id AND cp.operator_name IS NOT NULL AND lower(cp.operator_name) = lower(:operator)))");
        if (excludeOperators != null) sql.append(" AND (s.operator_name IS NULL OR lower(s.operator_name) NOT IN (:excludeOperators) "
                + "OR EXISTS (SELECT 1 FROM master.charge_point cp WHERE cp.station_id = s.id AND cp.operator_name IS NOT NULL "
                + "AND lower(cp.operator_name) NOT IN (:excludeOperators)))");
        if (includeOperators != null) sql.append(" AND (lower(s.operator_name) IN (:includeOperators) OR EXISTS (SELECT 1 "
                + "FROM master.charge_point cp WHERE cp.station_id = s.id AND cp.operator_name IS NOT NULL "
                + "AND lower(cp.operator_name) IN (:includeOperators)))");
    }

    private static void bindFilters(JdbcClient.StatementSpec query, List<String> connectorTypes, java.math.BigDecimal minPowerKw, String operator, List<String> excludeOperators, List<String> includeOperators) {
        if (connectorTypes != null) query.param("connectorTypes", lowerCase(connectorTypes));
        if (minPowerKw != null) query.param("minPowerKw", minPowerKw);
        if (operator != null) query.param("operator", operator);
        if (excludeOperators != null) query.param("excludeOperators", lowerCase(excludeOperators));
        if (includeOperators != null) query.param("includeOperators", lowerCase(includeOperators));
    }

    private static List<String> lowerCase(List<String> values) {
        return values.stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
    }
}
