package de.joinside.evmap_service.api.station;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
class StationService {
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
                                                  String connectorType,
                                                  BigDecimal minPowerKw,
                                                  String operator) {

        if (radiusKm < 1 || radiusKm > 100) throw new IllegalArgumentException("radiusKm must be between 1 and 100");
        return spatialStations.findNearby(latitude, longitude, radiusKm, connectorType, minPowerKw, operator);
    }

    StationController.StationDetail detail(UUID id) {
        ChargingStation station = stations
                .findById(id)
                .orElseThrow(() -> new StationController.StationNotFoundException(id));

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

        return new StationController.StationDetail(summary, connectorDtos, sourceNames);
    }
}
