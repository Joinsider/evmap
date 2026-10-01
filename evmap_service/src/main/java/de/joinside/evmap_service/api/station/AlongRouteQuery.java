package de.joinside.evmap_service.api.station;

import java.math.BigDecimal;
import java.util.List;

/**
 * One "stations along the route" query: a simplified polyline, the corridor around it and the same
 * filters as the map query (ADR 0017).
 * <p>
 * The polyline is a route someone is about to drive, so it is personal data in the sense of ADR 0002:
 * this record is never logged, only {@code route.size()} is.
 *
 * @param route            the simplified route in driving order, at least two points
 * @param corridorKm       how far off the line a station may be
 * @param connectorTypes   connector tokens to require, or {@code null} for any
 * @param excludeOperators networks the user hid, or {@code null}
 * @param includeOperators the allowlist of networks, or {@code null} for no restriction
 * @param limit            most stations returned; past it the strongest chargers are kept
 */
record AlongRouteQuery(List<StationController.RoutePoint> route, double corridorKm, List<String> connectorTypes,
                       BigDecimal minPowerKw, List<String> excludeOperators, List<String> includeOperators,
                       int limit) {

    /** Every empty list read as absent, as {@link NearbyQuery#normalized()} does. */
    AlongRouteQuery normalized() {
        return new AlongRouteQuery(route, corridorKm, absentIfEmpty(connectorTypes), minPowerKw,
                absentIfEmpty(excludeOperators), absentIfEmpty(includeOperators), limit);
    }

    private static List<String> absentIfEmpty(List<String> values) {
        return values == null || values.isEmpty() ? null : values;
    }
}
