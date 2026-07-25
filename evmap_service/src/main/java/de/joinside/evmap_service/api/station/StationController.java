package de.joinside.evmap_service.api.station;

import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/stations")
public class StationController {
    private final StationService stations;

    StationController(StationService stations) {
        this.stations = stations;
    }

    @GetMapping
    List<StationSummary> nearby(@RequestParam double latitude, @RequestParam double longitude, @RequestParam(defaultValue = "10") int radiusKm, @RequestParam(required = false) String connectorType, @RequestParam(required = false) BigDecimal minPowerKw, @RequestParam(required = false) String operator) {
        return stations.nearby(latitude, longitude, radiusKm, connectorType, minPowerKw, operator);
    }

    @GetMapping("/{id}")
    StationDetail detail(@PathVariable UUID id) {
        return stations.detail(id);
    }

    record StationSummary(UUID id, String displayName, String street, String city, String postalCode,
                          String countryCode, String operatorName, double latitude, double longitude,
                          String availabilityStatus) {
    }

    record Connector(String connectorType, BigDecimal powerKw, int quantity) {
    }

    record StationDetail(StationSummary station, List<Connector> connectors, List<String> sources) {
    }

    public static class StationNotFoundException extends RuntimeException {
        StationNotFoundException(UUID id) {
            super("Station not found: " + id);
        }
    }
}
