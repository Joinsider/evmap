package de.joinside.evmap_service.availability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param enabled          turns the whole live-availability axis off, providers included. Set false
 *                         for the sync deployable, which has no business polling live feeds.
 * @param ttl              how long a provider's answer for an area is reused. AFIR obliges operators
 *                         to publish within one minute of the event, so anything longer than that
 *                         throws away freshness the regulation already paid for, and anything much
 *                         shorter just re-asks for the same numbers.
 * @param maxCacheEntries  ceiling on cached areas. Each entry is one provider's answer for one
 *                         viewport; the cache is cleared wholesale when it overflows rather than
 *                         evicted in order, because every entry expires within {@code ttl} anyway and
 *                         a precise eviction policy would be more machinery than the problem.
 * @param stationRadius    radius of the box asked about when one station is opened. Large enough to
 *                         survive the disagreement between a register's coordinates and an operator's
 *                         own — matching is by identifier, so a wider box costs a bigger response and
 *                         never a wrong answer.
 * @param maxViewportSpan  widest viewport answered, in degrees. Beyond it the map is showing a
 *                         country and live pins mean nothing, while the upstream response would be
 *                         enormous.
 * @param maxChargePoints  ceiling on charge points considered for one viewport.
 */
@ConfigurationProperties("evmap.availability")
record AvailabilityProperties(@DefaultValue("true") boolean enabled,
                              @DefaultValue("60s") Duration ttl,
                              @DefaultValue("500") int maxCacheEntries,
                              @DefaultValue("300") double stationRadius,
                              @DefaultValue("1.5") double maxViewportSpan,
                              @DefaultValue("5000") int maxChargePoints) {
}
