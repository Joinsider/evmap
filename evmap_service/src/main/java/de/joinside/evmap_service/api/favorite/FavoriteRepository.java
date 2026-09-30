package de.joinside.evmap_service.api.favorite;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Favorites (ADR 0021). Plain SQL: the list joins the station and its strongest connector, the same
 * projection the map uses, and inserts skip stations that no longer exist instead of failing.
 */
@Repository
class FavoriteRepository {
    private static final String ACCOUNT = "account";
    private static final String STATION = "station";

    private final JdbcClient jdbc;

    FavoriteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<FavoriteController.FavoriteStation> list(UUID account) {
        return jdbc.sql("SELECT s.id, s.display_name, s.street, s.city, s.postal_code, s.country_code, s.operator_name, s.latitude, s.longitude, "
                        + "s.availability_status, p.max_power_kw, f.created_at "
                        + "FROM user_data.favorite_station f JOIN master.charging_station s ON s.id = f.station_id "
                        + "LEFT JOIN LATERAL (SELECT MAX(power_kw) AS max_power_kw FROM master.charging_connector WHERE station_id = s.id) p ON true "
                        + "WHERE f.account_id = :account ORDER BY f.created_at DESC, s.id")
                .param(ACCOUNT, account)
                .query((rs, row) -> new FavoriteController.FavoriteStation(rs.getObject("id", UUID.class), rs.getString("display_name"),
                        rs.getString("street"), rs.getString("city"), rs.getString("postal_code"), rs.getString("country_code"),
                        rs.getString("operator_name"), rs.getDouble("latitude"), rs.getDouble("longitude"),
                        rs.getString("availability_status"), rs.getBigDecimal("max_power_kw"), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    long count(UUID account) {
        return jdbc.sql("SELECT count(*) FROM user_data.favorite_station WHERE account_id = :account").param(ACCOUNT, account)
                .query(Long.class).single();
    }

    boolean stationExists(UUID station) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM master.charging_station WHERE id = :id)").param("id", station)
                .query(Boolean.class).single();
    }

    boolean isFavorite(UUID account, UUID station) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM user_data.favorite_station WHERE account_id = :account AND station_id = :station)")
                .param(ACCOUNT, account).param(STATION, station).query(Boolean.class).single();
    }

    /** Idempotent. Returns whether a row was added; a station that does not exist adds nothing. */
    boolean add(UUID account, UUID station) {
        return jdbc.sql("INSERT INTO user_data.favorite_station (account_id, station_id) "
                        + "SELECT :account, id FROM master.charging_station WHERE id = :station ON CONFLICT DO NOTHING")
                .param(ACCOUNT, account).param(STATION, station).update() > 0;
    }

    /** Idempotent: removing what is not there is not an error. */
    void remove(UUID account, UUID station) {
        jdbc.sql("DELETE FROM user_data.favorite_station WHERE account_id = :account AND station_id = :station")
                .param(ACCOUNT, account).param(STATION, station).update();
    }

    /** Ids among {@code candidates} that are known stations and not yet favorites of the account. */
    List<UUID> addable(UUID account, Collection<UUID> candidates) {
        if (candidates.isEmpty()) return List.of();
        return jdbc.sql("SELECT s.id FROM master.charging_station s WHERE s.id IN (:ids) AND NOT EXISTS "
                        + "(SELECT 1 FROM user_data.favorite_station f WHERE f.account_id = :account AND f.station_id = s.id)")
                .param("ids", candidates).param(ACCOUNT, account).query(UUID.class).list();
    }
}
