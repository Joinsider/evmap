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
                                                  String operator) {

        if (radiusKm < 1 || radiusKm > 100) {
            log.warn("Rejected nearby query with radiusKm={}", radiusKm);
            throw new IllegalArgumentException("radiusKm must be between 1 and 100");
        }
        var types = connectorTypes == null || connectorTypes.isEmpty() ? null : connectorTypes;
        log.debug("Nearby query lat={} lon={} radiusKm={} connectorTypes={} minPowerKw={} operator={}",
                latitude, longitude, radiusKm, types, minPowerKw, operator);

        long startedAt = System.nanoTime();
        var results = spatialStations.findNearby(latitude, longitude, radiusKm, types, minPowerKw, operator);
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;

        if (durationMs >= SLOW_QUERY_MS)
            log.warn("Slow nearby query: {} results in {} ms (lat={} lon={} radiusKm={})", results.size(), durationMs, latitude, longitude, radiusKm);
        else log.debug("Nearby query returned {} stations in {} ms", results.size(), durationMs);
        return results;
    }

    StationController.StationDetail detail(UUID id) {
        ChargingStation station = stations
                .findById(id)
                .orElseThrow(() -> {
                    log.warn("Station detail requested for unknown id {}", id);
                    return new StationController.StationNotFoundException(id);
                });

        var summary = new StationController.StationSummary(station.id,
                station.displayName,
                station.street,
                station.city,
                station.postalCode,
                station.countryCode,
                station.operatorName,
                station.latitude,
                station.longitude,
                station.availabilityStatus
        );

        var connectorDtos = connectors
                .findByStationId(id)
                .stream()
                .map(connector -> new StationController.Connector(
                        connector.connectorType,
                        connector.powerKw,
                        connector.quantity
                )).toList();

        var sourceNames = sources.findByStationIdOrderBySource(id)
                .stream()
                .map(source -> source.source)
                .distinct()
                .toList();

        log.debug("Station {} served with {} connectors from sources {}", id, connectorDtos.size(), sourceNames);
        return new StationController.StationDetail(summary, connectorDtos, sourceNames);
    }
}
