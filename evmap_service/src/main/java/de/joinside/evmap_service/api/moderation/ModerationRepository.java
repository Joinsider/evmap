package de.joinside.evmap_service.api.moderation;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reports and blocks (ADR 0020). Plain SQL: the queue is an aggregate over reports joined to the
 * comment and its station, and blocking resolves an author from a comment id without that id ever
 * leaving the database layer.
 */
@Repository
class ModerationRepository {
    private static final String COMMENT = "comment";
    private static final String CREATED_AT = "created_at";

    private final JdbcClient jdbc;

    ModerationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The comment's author, or empty if there is no such comment. */
    Optional<UUID> authorOf(UUID commentId) {
        return jdbc.sql("SELECT account_id FROM user_data.station_comment WHERE id = :id").param("id", commentId)
                .query(UUID.class).optional();
    }

    /** Idempotent: reporting the same comment twice keeps the first report. */
    void report(UUID commentId, UUID reporter, String reason) {
        jdbc.sql("INSERT INTO user_data.comment_report (id, comment_id, reporter_id, reason) VALUES (:id, :comment, :reporter, :reason) "
                        + "ON CONFLICT (comment_id, reporter_id) DO NOTHING")
                .param("id", UUID.randomUUID()).param(COMMENT, commentId).param("reporter", reporter).param("reason", reason).update();
    }

    void block(UUID blocker, UUID blocked) {
        jdbc.sql("INSERT INTO user_data.account_block (id, blocker_id, blocked_id) VALUES (:id, :blocker, :blocked) "
                        + "ON CONFLICT (blocker_id, blocked_id) DO NOTHING")
                .param("id", UUID.randomUUID()).param("blocker", blocker).param("blocked", blocked).update();
    }

    List<ModerationController.Block> blocks(UUID blocker) {
        return jdbc.sql("SELECT id, created_at FROM user_data.account_block WHERE blocker_id = :blocker ORDER BY created_at DESC")
                .param("blocker", blocker)
                .query((rs, row) -> new ModerationController.Block(rs.getObject("id", UUID.class), instant(rs, CREATED_AT))).list();
    }

    boolean unblock(UUID blockId, UUID blocker) {
        return jdbc.sql("DELETE FROM user_data.account_block WHERE id = :id AND blocker_id = :blocker")
                .param("id", blockId).param("blocker", blocker).update() > 0;
    }

    /** Open reports grouped by comment, the most recently reported first. */
    List<AdminReportsController.ReportedComment> openReports() {
        Map<UUID, AdminReportsController.ReportedComment> byComment = new LinkedHashMap<>();
        jdbc.sql("SELECT c.id AS comment_id, c.station_id, s.display_name AS station_name, c.body, c.paid_price_cents, c.experience, "
                        + "c.created_at AS comment_created_at, r.reason, r.created_at AS reported_at "
                        + "FROM user_data.comment_report r JOIN user_data.station_comment c ON c.id = r.comment_id "
                        + "LEFT JOIN master.charging_station s ON s.id = c.station_id "
                        + "WHERE r.status = 'open' ORDER BY r.created_at DESC")
                .query((RowCallbackHandler) rs -> {
                    UUID commentId = rs.getObject("comment_id", UUID.class);
                    AdminReportsController.ReportedComment reported = byComment.get(commentId);
                    if (reported == null) {
                        reported = new AdminReportsController.ReportedComment(commentId, rs.getObject("station_id", UUID.class),
                                rs.getString("station_name"), rs.getString("body"), (Integer) rs.getObject("paid_price_cents"),
                                rs.getString("experience"), instant(rs, "comment_created_at"), instant(rs, "reported_at"),
                                new LinkedHashMap<>());
                        byComment.put(commentId, reported);
                    }
                    reported.reasons().merge(rs.getString("reason"), 1, Integer::sum);
                });
        return List.copyOf(byComment.values());
    }

    /** Closes every open report on the comment without touching the comment. Returns how many there were. */
    int dismiss(UUID commentId, UUID admin) {
        return jdbc.sql("UPDATE user_data.comment_report SET status = 'dismissed', resolved_at = now(), resolved_by = :admin "
                        + "WHERE comment_id = :comment AND status = 'open'")
                .param("admin", admin).param(COMMENT, commentId).update();
    }

    /** Removes the comment; its reports go with it (ON DELETE CASCADE). */
    boolean removeComment(UUID commentId) {
        return jdbc.sql("DELETE FROM user_data.station_comment WHERE id = :id").param("id", commentId).update() > 0;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
