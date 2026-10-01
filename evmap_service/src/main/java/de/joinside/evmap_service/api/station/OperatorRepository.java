package de.joinside.evmap_service.api.station;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The operator directory, aggregated out of station master data.
 *
 * <p>There is no operator table: {@code operator_name} is a field the source adapters normalize
 * onto each station, and BNetzA/OCM publish no stable operator identifier to key one on. Grouping
 * the stations is therefore the directory — which also means the name <em>is</em> the identity, and
 * two spellings of one company are two entries. See ADR 0014.
 *
 * <p>A station counts for its own operator and for every other operator of its charge points, which the
 * ingestion stores only where they differ (stations bundled from several operators' sites, ADR 0022).
 */
@Repository
class OperatorRepository {
    private final JdbcClient jdbc;

    OperatorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Operators matching the search, most stations first so an empty query returns the networks a
     * user is most likely looking for rather than an alphabetical slice of the long tail.
     */
    List<OperatorController.Operator> search(OperatorSearch search) {
        StringBuilder sql = new StringBuilder("SELECT o.operator_name, COUNT(DISTINCT o.station_id) AS station_count FROM ("
                + "SELECT s.id AS station_id, s.operator_name FROM master.charging_station s "
                + "UNION ALL SELECT cp.station_id, cp.operator_name FROM master.charge_point cp WHERE cp.operator_name IS NOT NULL"
                + ") o WHERE o.operator_name IS NOT NULL AND o.operator_name <> ''");
        if (search.pattern() != null) sql.append(" AND lower(o.operator_name) LIKE lower(:pattern) ESCAPE '\\'");
        sql.append(" GROUP BY o.operator_name ORDER BY station_count DESC, o.operator_name LIMIT :limit");
        var query = jdbc.sql(sql.toString()).param("limit", search.limit());
        if (search.pattern() != null) query.param("pattern", search.pattern());
        return query.query((rs, row) -> new OperatorController.Operator(rs.getString("operator_name"), rs.getLong("station_count"))).list();
    }
}
