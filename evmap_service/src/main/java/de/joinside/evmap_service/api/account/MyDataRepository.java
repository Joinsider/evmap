package de.joinside.evmap_service.api.account;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything one account owns in {@code user_data}, read for the data export and "my contributions"
 * (ADR 0020). Whatever a phase adds to {@code user_data} later has to be added here too, or the export
 * stops being complete; {@code MyDataRepositoryTests} fails when a table is missing.
 * <p>
 * Deliberately absent: the encrypted Apple refresh token (a credential, not the person's data) and
 * anybody else's identity — blocks are listed by their own id only.
 */
@Repository
class MyDataRepository {
    private static final String ACCOUNT = "account";
    private static final String CREATED_AT = "created_at";

    private final JdbcClient jdbc;

    MyDataRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<MyDataController.AccountData> account(UUID accountId) {
        return jdbc.sql("SELECT id, is_admin, created_at, last_login_at FROM user_data.account WHERE id = :id").param("id", accountId)
                .query((rs, row) -> new MyDataController.AccountData(rs.getObject("id", UUID.class), rs.getBoolean("is_admin"),
                        instant(rs, CREATED_AT), instant(rs, "last_login_at"))).optional();
    }

    List<MyDataController.IdentityData> identities(UUID accountId) {
        return jdbc.sql("SELECT provider, provider_subject, email, email_verified, created_at, last_login_at "
                        + "FROM user_data.provider_identity WHERE account_id = :account ORDER BY created_at")
                .param(ACCOUNT, accountId)
                .query((rs, row) -> new MyDataController.IdentityData(rs.getString("provider"), rs.getString("provider_subject"),
                        rs.getString("email"), rs.getBoolean("email_verified"), instant(rs, CREATED_AT), instant(rs, "last_login_at"))).list();
    }

    List<MyDataController.CommentData> comments(UUID accountId) {
        return jdbc.sql("SELECT c.id, c.station_id, s.display_name AS station_name, c.body, c.paid_price_cents, c.experience, "
                        + "c.created_at, c.updated_at FROM user_data.station_comment c "
                        + "LEFT JOIN master.charging_station s ON s.id = c.station_id WHERE c.account_id = :account ORDER BY c.created_at DESC")
                .param(ACCOUNT, accountId)
                .query((rs, row) -> new MyDataController.CommentData(rs.getObject("id", UUID.class), rs.getObject("station_id", UUID.class),
                        rs.getString("station_name"), rs.getString("body"), (Integer) rs.getObject("paid_price_cents"),
                        rs.getString("experience"), instant(rs, CREATED_AT), instant(rs, "updated_at"))).list();
    }

    /** The reports this account filed. The reported comment's text is someone else's data and is not included. */
    List<MyDataController.ReportData> reports(UUID accountId) {
        return jdbc.sql("SELECT r.id, r.reason, r.status, r.created_at, r.resolved_at, s.display_name AS station_name "
                        + "FROM user_data.comment_report r JOIN user_data.station_comment c ON c.id = r.comment_id "
                        + "LEFT JOIN master.charging_station s ON s.id = c.station_id WHERE r.reporter_id = :account ORDER BY r.created_at DESC")
                .param(ACCOUNT, accountId)
                .query((rs, row) -> new MyDataController.ReportData(rs.getObject("id", UUID.class), rs.getString("reason"),
                        rs.getString("status"), rs.getString("station_name"), instant(rs, CREATED_AT), instant(rs, "resolved_at"))).list();
    }

    List<MyDataController.BlockData> blocks(UUID accountId) {
        return jdbc.sql("SELECT id, created_at FROM user_data.account_block WHERE blocker_id = :account ORDER BY created_at DESC")
                .param(ACCOUNT, accountId)
                .query((rs, row) -> new MyDataController.BlockData(rs.getObject("id", UUID.class), instant(rs, CREATED_AT))).list();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
