package de.joinside.evmap_service.api.comment;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Which comments of a station a signed-in reader does not get to see (ADR 0020): those of authors
 * they blocked, and those they reported themselves. Read from the moderation tables, which belong to
 * the account that acted, so this is the one place the comment list depends on them.
 */
@Repository
class CommentVisibility {
    private final JdbcClient jdbc;

    CommentVisibility(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Set<UUID> hiddenFor(UUID viewer, UUID stationId) {
        return new HashSet<>(jdbc.sql("""
                        SELECT c.id FROM user_data.station_comment c
                        WHERE c.station_id = :station
                          AND (EXISTS (SELECT 1 FROM user_data.account_block b WHERE b.blocker_id = :viewer AND b.blocked_id = c.account_id)
                            OR EXISTS (SELECT 1 FROM user_data.comment_report r WHERE r.reporter_id = :viewer AND r.comment_id = c.id))
                        """)
                .param("station", stationId).param("viewer", viewer).query(UUID.class).list());
    }
}
