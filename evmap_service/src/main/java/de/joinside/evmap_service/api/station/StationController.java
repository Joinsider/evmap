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

    /**
     * @param operator        exact operator name to restrict the result to. No current client sends
     *                        it — the app expresses provider choice through {@code excludeOperator}
     *                        (ADR 0014) — but installed older builds still do, so it stays.
     * @param excludeOperator operator names to leave out, repeated once per name. This is how the
     *                        app's "do not show this provider" setting reaches the query.
     * @param includeOperator operator names to restrict the result to, repeated once per name — the
     *                        allowlist the app sends once the user turns its global "show all other
     *                        providers" switch off. Absent means no restriction; there is
     *                        deliberately no way to spell "restrict to nothing", so a client that
     *                        wants an empty map must not send the request at all (ADR 0014).
     */
    @GetMapping
    List<StationSummary> nearby(@RequestParam double latitude, @RequestParam double longitude, @RequestParam(defaultValue = "10") int radiusKm, @RequestParam(required = false) List<String> connectorType, @RequestParam(required = false) BigDecimal minPowerKw, @RequestParam(required = false) String operator, @RequestParam(required = false) List<String> excludeOperator, @RequestParam(required = false) List<String> includeOperator, @RequestParam(defaultValue = "500") int limit) {
        return stations.nearby(new NearbyQuery(latitude, longitude, radiusKm, connectorType, minPowerKw, operator,
                excludeOperator, includeOperator, limit));
    }

    @GetMapping("/{id}")
    StationDetail detail(@PathVariable UUID id) {
        return stations.detail(id);
    }

    /**
     * @param maxPowerKw strongest connector at the station, {@code null} when no source reported a
     *                   power rating. Drives the map pin colour, so it is part of the list payload
     *                   rather than of the detail response.
     */
    record StationSummary(UUID id, String displayName, String street, String city, String postalCode,
                          String countryCode, String operatorName, double latitude, double longitude,
                          String availabilityStatus, BigDecimal maxPowerKw) {
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
