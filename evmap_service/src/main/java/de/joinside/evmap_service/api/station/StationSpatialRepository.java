package de.joinside.evmap_service.api.station;

import java.math.BigDecimal;
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
    List<StationController.StationSummary> findNearby(double latitude, double longitude, int radiusKm, List<String> connectorTypes, BigDecimal minPowerKw, String operator) {
        StringBuilder sql = new StringBuilder("SELECT DISTINCT " + COLUMNS + " FROM master.charging_station s LEFT JOIN master.charging_connector c ON c.station_id = s.id WHERE ST_DWithin(s.location, ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography, :radius)");
        if (connectorTypes != null) sql.append(" AND lower(c.connector_type) IN (:connectorTypes)"); if (minPowerKw != null) sql.append(" AND c.power_kw >= :minPowerKw"); if (operator != null) sql.append(" AND lower(s.operator_name) = lower(:operator)");
        var query = jdbc.sql(sql.toString()).param("latitude", latitude).param("longitude", longitude).param("radius", radiusKm * 1000.0);
        if (connectorTypes != null) query.param("connectorTypes", connectorTypes.stream().map(type -> type.toLowerCase(Locale.ROOT)).toList()); if (minPowerKw != null) query.param("minPowerKw", minPowerKw); if (operator != null) query.param("operator", operator);
        return query.query((rs, row) -> new StationController.StationSummary(UUID.fromString(rs.getString("id")), rs.getString("display_name"), rs.getString("street"), rs.getString("city"), rs.getString("postal_code"), rs.getString("country_code"), rs.getString("operator_name"), rs.getDouble("latitude"), rs.getDouble("longitude"), rs.getString("availability_status"))).list();
    }
}
