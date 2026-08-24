package de.joinside.evmap_service.api.station;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class OperatorService {
    private static final Logger log = LoggerFactory.getLogger(OperatorService.class);
    /** A directory lookup slower than this points at a missing index — worth a warning. */
    private static final long SLOW_QUERY_MS = 500;

    private final OperatorRepository operators;

    OperatorService(OperatorRepository operators) {
        this.operators = operators;
    }

    List<OperatorController.Operator> search(String query, int limit) {
        OperatorSearch search;
        try {
            search = OperatorSearch.of(query, limit);
        } catch (IllegalArgumentException e) {
            log.warn("Rejected operator query with limit={}", limit);
            throw e;
        }

        long startedAt = System.nanoTime();
        var results = operators.search(search);
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;

        // The query string is what somebody typed into a picker, not a location or a comment, so it
        // is safe to log — but it is only interesting while debugging.
        if (durationMs >= SLOW_QUERY_MS)
            log.warn("Slow operator query: {} results in {} ms (limit={})", results.size(), durationMs, limit);
        else log.debug("Operator query '{}' returned {} of at most {} operators in {} ms", query, results.size(), limit, durationMs);
        return results;
    }
}
