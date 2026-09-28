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
        StringBuilder sql = new StringBuilder("SELECT s.operator_name, COUNT(*) AS station_count FROM master.charging_station s WHERE s.operator_name IS NOT NULL AND s.operator_name <> ''");
        if (search.pattern() != null) sql.append(" AND lower(s.operator_name) LIKE lower(:pattern) ESCAPE '\\'");
        sql.append(" GROUP BY s.operator_name ORDER BY station_count DESC, s.operator_name LIMIT :limit");
        var query = jdbc.sql(sql.toString()).param("limit", search.limit());
        if (search.pattern() != null) query.param("pattern", search.pattern());
        return query.query((rs, row) -> new OperatorController.Operator(rs.getString("operator_name"), rs.getLong("station_count"))).list();
    }
}
