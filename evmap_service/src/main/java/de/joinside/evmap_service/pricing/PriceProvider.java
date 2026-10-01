package de.joinside.evmap_service.pricing;

import de.joinside.evmap_service.availability.Attribution;
import de.joinside.evmap_service.availability.GeoBounds;

import java.util.List;

/**
 * One tariff source, normalized onto {@link ChargePointPrice}. The counterpart to
 * {@link de.joinside.evmap_service.availability.AvailabilityProvider}, with the same contract.
 */
public interface PriceProvider {

    /** Stable token for logs; never shown to users — they see {@link #attribution()}. */
    String source();

    /** How the source is credited next to its prices; every source so far requires it. */
    Attribution attribution();

    default boolean enabled() {
        return true;
    }

    /** Whether this provider claims to know anything about a country (ISO 3166-1 alpha-2). */
    boolean covers(String countryCode);

    /**
     * Prices for every charge point this provider knows inside {@code bounds}.
     * <p>
     * Returns an empty list rather than throwing when the source is unreachable or malformed: a tariff feed
     * being down must leave the price unknown and the station screen working. Identifiers are normalized.
     */
    List<ChargePointPrice> fetch(GeoBounds bounds);
}
