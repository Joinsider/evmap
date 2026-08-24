package de.joinside.evmap_service.api.station;

import de.joinside.evmap_service.availability.AvailabilityService;
import de.joinside.evmap_service.availability.GeoBounds;
import de.joinside.evmap_service.availability.StationAvailability;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Live availability, served separately from the station itself.
 * <p>
 * Deliberately not a field on the station payload. Station reads are cacheable for hours and this is
 * not, and a live source that is down has to degrade to "unknown" without taking the map with it —
 * which it cannot do if its answer is welded into the response the map depends on.
 * <p>
 * Both paths sit under {@code /api/v1/stations/**}, which {@code SecurityConfiguration} already
 * permits unauthenticated: live occupancy of a public charge point is public information, and
 * requiring a sign-in to see it would gate the app's most useful screen behind Apple.
 */
@RestController
@RequestMapping("/api/v1/stations")
@ConditionalOnBean(AvailabilityService.class)
class AvailabilityController {
    private final AvailabilityService availability;

    AvailabilityController(AvailabilityService availability) {
        this.availability = availability;
    }

    /**
     * Live state of one station, with its charge points.
     * <p>
     * 404 when the station does not exist, rather than an unknown status: an id that means nothing is
     * a client bug, and answering it with a plausible-looking "we don't know" would hide it.
     */
    @GetMapping("/{id}/availability")
    StationAvailabilityResponse forStation(@PathVariable UUID id) {
        return availability.forStation(id)
                .map(AvailabilityController::toResponse)
                .orElseThrow(() -> new StationController.StationNotFoundException(id));
    }

    /**
     * Live state for a viewport, for the map.
     * <p>
     * Answers only the stations that have a live status — the map already draws every station from
     * the station query, and an unknown status changes nothing about a pin. A viewport wider than the
     * configured span answers empty rather than failing: at that zoom the pins would be meaningless
     * and the upstream response enormous.
     */
    @GetMapping("/availability")
    List<StationAvailabilityResponse> inViewport(@RequestParam double latMin, @RequestParam double lonMin,
                                                 @RequestParam double latMax, @RequestParam double lonMax) {
        return availability.inBounds(new GeoBounds(latMin, lonMin, latMax, lonMax))
                .stream()
                .map(AvailabilityController::toResponse)
                .toList();
    }

    /**
     * @param status     the station-level summary over the {@code LiveAvailability} vocabulary the iOS
     *                   client mirrors.
     * @param observedAt when the newest underlying reading was taken, {@code null} when there is
     *                   none. The client renders its age and shows nothing rather than a bare value —
     *                   a live status without its timestamp cannot be told from a stale one.
     */
    record StationAvailabilityResponse(UUID stationId, String status, int available, int occupied,
                                       int outOfOrder, int unknown, Instant observedAt,
                                       List<ChargePointResponse> chargePoints) {
    }

    record ChargePointResponse(UUID id, String evseId, String status, Instant observedAt) {
    }

    private static StationAvailabilityResponse toResponse(StationAvailability availability) {
        return new StationAvailabilityResponse(
                availability.stationId(),
                availability.status(),
                availability.available(),
                availability.occupied(),
                availability.outOfOrder(),
                availability.unknown(),
                availability.observedAt(),
                availability.chargePoints().stream()
                        .map(chargePoint -> new ChargePointResponse(chargePoint.chargePointId(),
                                chargePoint.evseId(), chargePoint.status(), chargePoint.observedAt()))
                        .toList());
    }
}
