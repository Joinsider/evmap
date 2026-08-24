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

    List<StationController.StationSummary> nearby(double latitude,
                                                  double longitude,
                                                  int radiusKm,
                                                  List<String> connectorTypes,
                                                  BigDecimal minPowerKw,
                                                  String operator,
                                                  List<String> excludeOperators,
                                                  List<String> includeOperators,
                                                  int limit) {

        if (radiusKm < 1 || radiusKm > MAX_RADIUS_KM) {
            log.warn("Rejected nearby query with radiusKm={}", radiusKm);
            throw new IllegalArgumentException("radiusKm must be between 1 and " + MAX_RADIUS_KM);
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            log.warn("Rejected nearby query with limit={}", limit);
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        if (excludeOperators != null && excludeOperators.size() > MAX_EXCLUDED_OPERATORS) {
            log.warn("Rejected nearby query excluding {} operators", excludeOperators.size());
            throw new IllegalArgumentException("excludeOperator must name at most " + MAX_EXCLUDED_OPERATORS + " operators");
        }
        if (includeOperators != null && includeOperators.size() > MAX_INCLUDED_OPERATORS) {
            log.warn("Rejected nearby query restricted to {} operators", includeOperators.size());
            throw new IllegalArgumentException("includeOperator must name at most " + MAX_INCLUDED_OPERATORS + " operators");
        }
        var types = connectorTypes == null || connectorTypes.isEmpty() ? null : connectorTypes;
        var excluded = excludeOperators == null || excludeOperators.isEmpty() ? null : excludeOperators;
        // An empty list is treated as "no allowlist", matching how the parameter's absence reads.
        // The client never gets here with one — an allowlist of nothing means an empty map, which
        // it answers without a request rather than by sending a query that cannot express it.
        var included = includeOperators == null || includeOperators.isEmpty() ? null : includeOperators;
        // The excluded names are logged by count only: which networks somebody switched off is a
        // preference of theirs, and the list adds nothing to a query trace anyway.
        log.debug("Nearby query lat={} lon={} radiusKm={} connectorTypes={} minPowerKw={} operator={} excludedOperators={} includedOperators={} limit={}",
                latitude, longitude, radiusKm, types, minPowerKw, operator, excluded == null ? 0 : excluded.size(), included == null ? 0 : included.size(), limit);

        long startedAt = System.nanoTime();
        var results = spatialStations.findNearby(latitude, longitude, radiusKm, types, minPowerKw, operator, excluded, included, limit);
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;

        if (durationMs >= SLOW_QUERY_MS)
            log.warn("Slow nearby query: {} results in {} ms (lat={} lon={} radiusKm={})", results.size(), durationMs, latitude, longitude, radiusKm);
        else log.debug("Nearby query returned {} stations in {} ms", results.size(), durationMs);
        // The client cannot tell a saturated viewport from an empty region otherwise.
        if (results.size() == limit) log.debug("Nearby query hit the {} row limit — result is the highest-powered slice", limit);
        return results;
    }

    StationController.StationDetail detail(UUID id) {
        ChargingStation station = stations
                .findById(id)
                .orElseThrow(() -> {
                    log.warn("Station detail requested for unknown id {}", id);
                    return new StationController.StationNotFoundException(id);
                });

        var connectorDtos = connectors
                .findByStationId(id)
                .stream()
                .map(connector -> new StationController.Connector(
                        connector.connectorType,
                        connector.powerKw,
                        connector.quantity
                )).toList();

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
}
