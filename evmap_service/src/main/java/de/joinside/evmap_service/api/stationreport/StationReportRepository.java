package de.joinside.evmap_service.api.stationreport;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Station reports (ADR 0021). Plain SQL: the queue is an aggregate over open reports joined to their
 * station, and the writes lean on the partial unique index for "one open report per account, station
 * and reason".
 */
@Repository
class StationReportRepository {
    /** Free texts shown per queue line; ten people describing one defect do not need ten paragraphs. */
    private static final int NOTES_PER_LINE = 5;

    private final JdbcClient jdbc;

    StationReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean stationExists(UUID station) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM master.charging_station WHERE id = :id)").param("id", station)
                .query(Boolean.class).single();
    }

    /** Idempotent while a report of the same reporter, station and reason is open. Returns whether one was added. */
    boolean report(UUID station, UUID reporter, String reason, String note) {
        return jdbc.sql("INSERT INTO user_data.station_report (id, station_id, reporter_id, reason, note) "
                        + "VALUES (:id, :station, :reporter, :reason, :note) "
                        + "ON CONFLICT (reporter_id, station_id, reason) WHERE status = 'open' DO NOTHING")
                .param("id", UUID.randomUUID()).param("station", station).param("reporter", reporter).param("reason", reason)
                .param("note", note).update() > 0;
    }

    /** Open reports grouped by station and reason, the most recently reported first. */
    List<StationReportController.ReportedStation> openGroups() {
        record Key(UUID station, String reason) {
        }
        Map<Key, Line> lines = new LinkedHashMap<>();
        jdbc.sql("SELECT r.station_id, s.display_name, s.operator_name, s.city, r.reason, r.note, r.created_at "
                        + "FROM user_data.station_report r JOIN master.charging_station s ON s.id = r.station_id "
                        + "WHERE r.status = 'open' ORDER BY r.created_at DESC")
                .query((RowCallbackHandler) rs -> {
                    Key key = new Key(rs.getObject("station_id", UUID.class), rs.getString("reason"));
                    Line line = lines.get(key);
                    if (line == null) {
                        line = new Line(key.station(), rs.getString("display_name"), rs.getString("operator_name"), rs.getString("city"),
                                key.reason(), instant(rs, "created_at"));
                        lines.put(key, line);
                    }
                    line.count++;
                    String note = rs.getString("note");
                    if (note != null && line.notes.size() < NOTES_PER_LINE) line.notes.add(note);
                });
        return lines.values().stream().map(Line::toResponse).toList();
    }

    /** The rows of one queue line while they are being read; the first row is the newest. */
    private static final class Line {
        private final UUID station;
        private final String name;
        private final String operator;
        private final String city;
        private final String reason;
        private final Instant lastReportedAt;
        private final List<String> notes = new ArrayList<>();
        private int count;

        Line(UUID station, String name, String operator, String city, String reason, Instant lastReportedAt) {
            this.station = station;
            this.name = name;
            this.operator = operator;
            this.city = city;
            this.reason = reason;
            this.lastReportedAt = lastReportedAt;
        }

        StationReportController.ReportedStation toResponse() {
            return new StationReportController.ReportedStation(station, name, operator, city, reason, count, lastReportedAt, List.copyOf(notes));
        }
    }

    /** Closes every open report of the station for that reason with the given status. Returns how many there were. */
    int close(UUID station, String reason, UUID admin, String status) {
        return jdbc.sql("UPDATE user_data.station_report SET status = :status, resolved_at = now(), resolved_by = :admin "
                        + "WHERE station_id = :station AND reason = :reason AND status = 'open'")
                .param("status", status).param("admin", admin).param("station", station).param("reason", reason).update();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
