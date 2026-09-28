package de.joinside.evmap_service.api.station;

import java.math.BigDecimal;
import java.util.List;

/**
 * One map query as the client asked it: a circle, the filters over it, and a row limit.
 * <p>
 * A value rather than nine positional parameters, so that the controller, the service and the
 * spatial repository cannot disagree about the order of two adjacent {@code List<String>}s — the
 * operator deny- and allowlist mean opposite things and share a type.
 *
 * @param connectorTypes   connector tokens to require, or {@code null} for any
 * @param excludeOperators networks the user hid, or {@code null}
 * @param includeOperators the allowlist of networks, or {@code null} for no restriction
 */
record NearbyQuery(double latitude, double longitude, int radiusKm, List<String> connectorTypes,
                   BigDecimal minPowerKw, String operator, List<String> excludeOperators,
                   List<String> includeOperators, int limit) {

    /**
     * The same query with every empty list read as absent, which is how the SQL treats a missing
     * filter. An empty allowlist in particular means "no allowlist": the client never sends one — an
     * allowlist of nothing means an empty map, which it answers without a request.
     */
    NearbyQuery normalized() {
        return new NearbyQuery(latitude, longitude, radiusKm, absentIfEmpty(connectorTypes), minPowerKw,
                operator, absentIfEmpty(excludeOperators), absentIfEmpty(includeOperators), limit);
    }

    private static List<String> absentIfEmpty(List<String> values) {
        return values == null || values.isEmpty() ? null : values;
    }

    static int sizeOf(List<String> values) {
        return values == null ? 0 : values.size();
    }
}
