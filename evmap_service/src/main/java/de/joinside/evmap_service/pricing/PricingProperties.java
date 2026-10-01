package de.joinside.evmap_service.pricing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param enabled         turns prices off, providers included; false for the sync deployable
 * @param ttl             how long a provider's answer for an area is reused. Tariffs change rarely compared with
 *                        status, and the client asks once per opened station, so this trades freshness against
 *                        upstream requests; ADR 0022 settled on 15 minutes.
 * @param maxCacheEntries ceiling on cached areas; the cache is cleared wholesale when it overflows
 * @param stationRadius   radius of the box asked about for one station, in metres; matching is by identifier, so
 *                        a wider box costs a larger response and never a wrong answer
 */
@ConfigurationProperties("evmap.pricing")
record PricingProperties(@DefaultValue("true") boolean enabled,
                         @DefaultValue("15m") Duration ttl,
                         @DefaultValue("500") int maxCacheEntries,
                         @DefaultValue("300") double stationRadius) {
}
