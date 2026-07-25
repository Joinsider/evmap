package de.joinside.evmap_service.sync;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.stream.Stream;

@Repository
@ConditionalOnProperty(name = "evmap.sync.enabled", havingValue = "true")
class PostgresStationIngestionRepository implements StationIngestionPort {
    private final JdbcClient jdbc;

    PostgresStationIngestionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void upsert(Stream<SourceStation> stations) {
        stations.forEach(this::upsertOne);
    }

    private void upsertOne(SourceStation source) {
        UUID stationId = jdbc.sql("SELECT station_id " +
                        "FROM master.station_source " +
                        "WHERE source=:source AND source_station_id=:sourceId")
                .param("source", source.source())
                .param("sourceId", source.sourceStationId())
                .query(UUID.class).optional().orElseGet(() -> nearby(source));
        if (stationId == null) {
            stationId = UUID.randomUUID();
            jdbc.sql("INSERT INTO master.charging_station " +
                            "(id, display_name, street, city, postal_code, country_code, operator_name, latitude, longitude) " +
                            "VALUES (:id,:name,:street,:city,:postal,:country,:operator,:latitude,:longitude)")
                    .param("id", stationId)
                    .param("name", source.name())
                    .param("street", source.street())
                    .param("city", source.city())
                    .param("postal", source.postalCode())
                    .param("country", source.countryCode())
                    .param("operator", source.operatorName())
                    .param("latitude", source.latitude())
                    .param("longitude", source.longitude())
                    .update();
        } else if ("BNetzA".equals(source.source()) || !isGerman(stationId)) {
            jdbc.sql("UPDATE master.charging_station " +
                    "SET display_name=:name, street=:street, city=:city, " +
                    "postal_code=:postal, country_code=:country, operator_name=:operator, " +
                    "latitude=:latitude, longitude=:longitude, updated_at=now() WHERE id=:id")
                    .param("id", stationId)
                    .param("name", source.name())
                    .param("street", source.street())
                    .param("city", source.city())
                    .param("postal", source.postalCode())
                    .param("country", source.countryCode())
                    .param("operator", source.operatorName())
                    .param("latitude", source.latitude())
                    .param("longitude", source.longitude())
                    .update();
        }
        jdbc.sql("INSERT INTO master.station_source " +
                "(station_id, source, source_station_id, last_updated_at) " +
                "VALUES (:stationId,:source,:sourceId,:updated) " +
                "ON CONFLICT (source, source_station_id) " +
                "DO UPDATE SET station_id=EXCLUDED.station_id, last_updated_at=EXCLUDED.last_updated_at")
                .param("stationId", stationId)
                .param("source", source.source())
                .param("sourceId", source.sourceStationId())
                .param("updated", source.lastUpdatedAt())
                .update();
    }

    private UUID nearby(SourceStation source) {
        return jdbc.sql("SELECT id FROM master.charging_station " +
                "WHERE country_code=:country AND ST_DWithin(location, ST_SetSRID(ST_MakePoint(:longitude,:latitude),4326)::geography, 30) " +
                "ORDER BY location <-> ST_SetSRID(ST_MakePoint(:longitude,:latitude),4326)::geography LIMIT 1")
                .param("country", source.countryCode())
                .param("latitude", source.latitude())
                .param("longitude", source.longitude())
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    private boolean isGerman(UUID stationId) {
        return jdbc.sql("SELECT country_code FROM master.charging_station WHERE id=:id")
                .param("id", stationId)
                .query(String.class).single().equalsIgnoreCase("DE");
    }
}
