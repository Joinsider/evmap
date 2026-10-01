package de.joinside.evmap_service.api.station;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
class StationService {
    private static final Logger log = LoggerFactory.getLogger(StationService.class);
    /** A spatial lookup slower than this points at a missing/unused index — worth a warning. */
    private static final long SLOW_QUERY_MS = 1_000;
    /**
     * Widest query the map may ask for. A viewport zoomed out to a continent turns into a radius of
     * this order; beyond it the circle stops describing anything the user can act on, and the cost
     * of the aggregate grows without bound.
     */
    private static final int MAX_RADIUS_KM = 1_000;
    /** Ceiling on returned rows — an unbounded list of pins is unusable on a map and slow to draw. */
    private static final int MAX_LIMIT = 2_000;
    /**
     * Ceiling on hidden operators. The client sends one name per network the user switched off, and
     * a plausible user hides a handful; a list this long is a malformed or hostile request, not a
     * setting, and every entry costs a comparison per candidate row.
     */
    static final int MAX_EXCLUDED_OPERATORS = 200;
    /**
     * Ceiling on the operator allowlist, for the same reason and at the same size: the app sends
     * one name per network the user kept visible while hiding the rest, and that is a list a person
     * curated by hand.
     */
    static final int MAX_INCLUDED_OPERATORS = 200;

    /** A simplified route of a few hundred points is what the client sends (ADR 0017); beyond this it is not one. */
    static final int MAX_ROUTE_POINTS = 500;
    static final double MIN_CORRIDOR_KM = 0.1;
    static final double MAX_CORRIDOR_KM = 25;
    /** Ceiling on stations along a route; a list of pins and detour minutes a person reads is far shorter. */
    static final int MAX_ALONG_ROUTE_LIMIT = 200;

    private final StationSpatialRepository spatialStations;
    private final ChargingStationRepository stations;
    private final ChargingConnectorRepository connectors;
    private final StationSourceRepository sources;

    StationService(StationSpatialRepository spatialStations,
                   ChargingStationRepository stations,
                   ChargingConnectorRepository connectors,
                   StationSourceRepository sources) {
        this.spatialStations = spatialStations;
        this.stations = stations;
        this.connectors = connectors;
        this.sources = sources;
    }

    List<StationController.StationSummary> nearby(NearbyQuery request) {
        validate(request);
        NearbyQuery query = request.normalized();
        // The excluded names are logged by count only: which networks somebody switched off is a
        // preference of theirs, and the list adds nothing to a query trace anyway.
        log.debug("Nearby query lat={} lon={} radiusKm={} connectorTypes={} minPowerKw={} operator={} excludedOperators={} includedOperators={} limit={}",
                query.latitude(), query.longitude(), query.radiusKm(), query.connectorTypes(), query.minPowerKw(),
                query.operator(), NearbyQuery.sizeOf(query.excludeOperators()),
                NearbyQuery.sizeOf(query.includeOperators()), query.limit());

        long startedAt = System.nanoTime();
        var results = spatialStations.findNearby(query);
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;

        if (durationMs >= SLOW_QUERY_MS)
            log.warn("Slow nearby query: {} results in {} ms (lat={} lon={} radiusKm={})", results.size(), durationMs,
                    query.latitude(), query.longitude(), query.radiusKm());
        else log.debug("Nearby query returned {} stations in {} ms", results.size(), durationMs);
        // The client cannot tell a saturated viewport from an empty region otherwise.
        if (results.size() == query.limit())
            log.debug("Nearby query hit the {} row limit — result is the highest-powered slice", query.limit());
        return results;
    }

    /**
     * Stations in a corridor around a route, in driving order. Nothing of the route is logged, not even
     * at debug: it is where somebody is going (ADR 0002), and the number of points says all a trace needs.
     */
    List<StationController.RouteStation> alongRoute(AlongRouteQuery request) {
        validate(request);
        AlongRouteQuery query = request.normalized();
        log.debug("Along-route query points={} corridorKm={} connectorTypes={} minPowerKw={} limit={}",
                query.route().size(), query.corridorKm(), query.connectorTypes(), query.minPowerKw(), query.limit());

        long startedAt = System.nanoTime();
        var results = spatialStations.findAlongRoute(query);
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;

        if (durationMs >= SLOW_QUERY_MS)
            log.warn("Slow along-route query: {} results in {} ms (points={} corridorKm={})", results.size(), durationMs,
                    query.route().size(), query.corridorKm());
        else log.debug("Along-route query returned {} stations in {} ms", results.size(), durationMs);
        return results;
    }

    private static void validate(AlongRouteQuery query) {
        if (query.route() == null || query.route().size() < 2 || query.route().size() > MAX_ROUTE_POINTS) {
            log.warn("Rejected along-route query with {} points", query.route() == null ? 0 : query.route().size());
            throw new IllegalArgumentException("route must have between 2 and " + MAX_ROUTE_POINTS + " points");
        }
        for (var point : query.route())
            if (point == null || !(Math.abs(point.latitude()) <= 90) || !(Math.abs(point.longitude()) <= 180)) {
                log.warn("Rejected along-route query with a point outside the coordinate range");
                throw new IllegalArgumentException("route points must be valid latitudes and longitudes");
            }
        if (!(query.corridorKm() >= MIN_CORRIDOR_KM && query.corridorKm() <= MAX_CORRIDOR_KM)) {
            log.warn("Rejected along-route query with corridorKm={}", query.corridorKm());
            throw new IllegalArgumentException("corridorKm must be between " + MIN_CORRIDOR_KM + " and " + MAX_CORRIDOR_KM);
        }
        if (query.limit() < 1 || query.limit() > MAX_ALONG_ROUTE_LIMIT) {
            log.warn("Rejected along-route query with limit={}", query.limit());
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_ALONG_ROUTE_LIMIT);
        }
        if (NearbyQuery.sizeOf(query.excludeOperators()) > MAX_EXCLUDED_OPERATORS) {
            log.warn("Rejected along-route query excluding {} operators", NearbyQuery.sizeOf(query.excludeOperators()));
            throw new IllegalArgumentException("excludeOperator must name at most " + MAX_EXCLUDED_OPERATORS + " operators");
        }
        if (NearbyQuery.sizeOf(query.includeOperators()) > MAX_INCLUDED_OPERATORS) {
            log.warn("Rejected along-route query restricted to {} operators", NearbyQuery.sizeOf(query.includeOperators()));
            throw new IllegalArgumentException("includeOperator must name at most " + MAX_INCLUDED_OPERATORS + " operators");
        }
    }

    /** Rejects a query outside the bounds the API promises, before it costs a database round trip. */
    private static void validate(NearbyQuery query) {
        if (query.radiusKm() < 1 || query.radiusKm() > MAX_RADIUS_KM) {
            log.warn("Rejected nearby query with radiusKm={}", query.radiusKm());
            throw new IllegalArgumentException("radiusKm must be between 1 and " + MAX_RADIUS_KM);
        }
        if (query.limit() < 1 || query.limit() > MAX_LIMIT) {
            log.warn("Rejected nearby query with limit={}", query.limit());
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        int excluded = NearbyQuery.sizeOf(query.excludeOperators());
        if (excluded > MAX_EXCLUDED_OPERATORS) {
            log.warn("Rejected nearby query excluding {} operators", excluded);
            throw new IllegalArgumentException("excludeOperator must name at most " + MAX_EXCLUDED_OPERATORS + " operators");
        }
        int included = NearbyQuery.sizeOf(query.includeOperators());
        if (included > MAX_INCLUDED_OPERATORS) {
            log.warn("Rejected nearby query restricted to {} operators", included);
            throw new IllegalArgumentException("includeOperator must name at most " + MAX_INCLUDED_OPERATORS + " operators");
        }
    }

    StationController.StationDetail detail(UUID id) {
        ChargingStation station = stations
                .findById(id)
                .orElseThrow(() -> {
                    log.warn("Station detail requested for unknown id {}", id);
                    return new StationController.StationNotFoundException(id);
                });

        var connectorDtos = aggregate(connectors.findByStationId(id));

        var maxPowerKw = connectorDtos.stream()
                .map(StationController.Connector::powerKw)
                .filter(java.util.Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(null);

        var summary = new StationController.StationSummary(station.id,
                station.displayName,
                station.street,
                station.city,
                station.postalCode,
                station.countryCode,
                station.operatorName,
                station.latitude,
                station.longitude,
                station.availabilityStatus,
                maxPowerKw
        );

        var sourceNames = sources.findByStationIdOrderBySource(id)
                .stream()
                .map(source -> source.source)
                .distinct()
                .toList();

        log.debug("Station {} served with {} connectors from sources {}", id, connectorDtos.size(), sourceNames);
        return new StationController.StationDetail(summary, connectorDtos, sourceNames);
    }

    /**
     * Sums the station's connector rows per (type, power).
     * <p>
     * Since the charge point inventory landed (ADR 0015), sources that describe charge points
     * individually store one connector row per charge point — four 22 kW Type 2 posts are four rows
     * of quantity one rather than a single row of quantity four. The client asks "what can I plug in
     * here, and how many", so the totals are rebuilt on read; the finer rows stay in the database,
     * where live availability needs them.
     * <p>
     * Insertion-ordered so the response keeps the order the ingestion wrote, and a station whose
     * connectors already were totals — Open Charge Map reports only those — passes through unchanged.
     */
    private static List<StationController.Connector> aggregate(List<ChargingConnector> connectors) {
        record Plug(String connectorType, BigDecimal powerKw) {
        }

        var quantities = new java.util.LinkedHashMap<Plug, Integer>();
        for (ChargingConnector connector : connectors)
            quantities.merge(new Plug(connector.connectorType, connector.powerKw), connector.quantity, Integer::sum);

        return quantities.entrySet().stream()
                .map(entry -> new StationController.Connector(
                        entry.getKey().connectorType(), entry.getKey().powerKw(), entry.getValue()))
                .toList();
    }
}
