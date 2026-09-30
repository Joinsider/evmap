package de.joinside.evmap_service.api.admin;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Reads the run history the sync deployable writes into {@code master.sync_run} — read-only, like all
 * master data in the API, and without going through anything in {@code sync}.
 */
@Repository
class AdminRepository {
    private final JdbcClient jdbc;

    AdminRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    AdminController.Overview overview() {
        return jdbc.sql("SELECT (SELECT count(*) FROM master.charging_station) AS stations, "
                        + "(SELECT count(*) FROM master.charge_point) AS charge_points, "
                        + "(SELECT count(*) FROM user_data.account) AS accounts, "
                        + "(SELECT count(*) FROM user_data.station_comment) AS comments, "
                        + "(SELECT count(DISTINCT comment_id) FROM user_data.comment_report WHERE status = 'open') AS open_reports, "
                        + "(SELECT count(DISTINCT (station_id, reason)) FROM user_data.station_report WHERE status = 'open') AS open_station_reports")
                .query((rs, row) -> new AdminController.Overview(rs.getLong("stations"), rs.getLong("charge_points"),
                        rs.getLong("accounts"), rs.getLong("comments"), rs.getLong("open_reports"), rs.getLong("open_station_reports")))
                .single();
    }

    List<AdminController.SyncRun> syncRuns(int limit) {
        return jdbc.sql("SELECT id, started_at, finished_at, status, processed, created, updated, unchanged, failed, error_message "
                        + "FROM master.sync_run ORDER BY started_at DESC LIMIT :limit")
                .param("limit", limit)
                .query((rs, row) -> new AdminController.SyncRun(rs.getObject("id", java.util.UUID.class),
                        instant(rs, "started_at"), instant(rs, "finished_at"), rs.getString("status"), rs.getInt("processed"),
                        rs.getInt("created"), rs.getInt("updated"), rs.getInt("unchanged"), rs.getInt("failed"),
                        rs.getString("error_message")))
                .list();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
