package de.joinside.evmap_service.availability;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Live state of one station, summarized from its charge points.
 *
 * @param status       the station-level answer, one of {@link LiveAvailability}. A station counts as
 *                     {@link LiveAvailability#AVAILABLE} as soon as one charge point is free, because
 *                     that is the question being asked — "can I charge here" — and only falls back to
 *                     {@link LiveAvailability#UNKNOWN} when nothing about it is known.
 * @param available    charge points free right now.
 * @param occupied     charge points in use, reserved or blocked.
 * @param outOfOrder   charge points the operator reports as broken or switched off.
 * @param unknown      charge points with no live answer — no EVSE-ID published, or none reported. Sent
 *                     to the client rather than hidden, so "2 von 4 frei" cannot be read off a
 *                     station where the other two are simply unknown.
 * @param observedAt   the most recent observation behind this summary, or {@code null} when there is
 *                     none. The client renders its age; a value without one would be a stale reading
 *                     presented as current.
 * @param chargePoints per-charge-point detail, in ingestion order. Empty for the map, which needs
 *                     only the summary and the counts.
 * @param sources      every source that contributed a status to this answer, in the order the
 *                     providers were asked — the credit their licences require. Empty when nothing
 *                     resolved, so an unknown station never names a source that told us nothing.
 */
public record StationAvailability(UUID stationId, String status, int available, int occupied,
                                  int outOfOrder, int unknown, Instant observedAt,
                                  List<ChargePointStatus> chargePoints, List<Attribution> sources) {

    public StationAvailability {
        chargePoints = chargePoints == null ? List.of() : List.copyOf(chargePoints);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /**
     * @param evseId the EVSE-ID as published, not the normalized comparison form — this one is shown
     *               to users and copied into charging apps, so it must read as the operator wrote it.
     * @param source {@link Attribution#name()} of the source that reported this status, {@code null}
     *               when none did. Per charge point because Lastenheft §5 asks for provenance at that
     *               level, and a station can be answered by more than one source.
     */
    public record ChargePointStatus(UUID chargePointId, String evseId, String status, Instant observedAt,
                                    String source) {
    }

    /** A station nothing is known about: no charge points resolved, no provider answered. */
    public static StationAvailability unknown(UUID stationId, int chargePointCount) {
        return new StationAvailability(stationId, LiveAvailability.UNKNOWN, 0, 0, 0, chargePointCount, null,
                List.of(), List.of());
    }

    /** Whether anything here is worth sending to a map client. */
    public boolean isKnown() {
        return !LiveAvailability.UNKNOWN.equals(status);
    }

    /** The same summary without the per-charge-point list, for the viewport response. */
    public StationAvailability withoutDetail() {
        return new StationAvailability(stationId, status, available, occupied, outOfOrder, unknown, observedAt,
                List.of(), sources);
    }
}
