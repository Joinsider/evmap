package de.joinside.evmap_service.pricing;

import de.joinside.evmap_service.availability.GeoBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Short-lived in-process cache of provider answers, keyed by source and area — the same design as
 * {@code availability.AvailabilityCache}, with the same caveat: correct for exactly one API container
 * (ADR 0015, "The cache is in-process").
 */
class PriceCache {
    private static final Logger log = LoggerFactory.getLogger(PriceCache.class);

    private record Entry(Instant expiresAt, List<ChargePointPrice> value) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final int maxEntries;
    private final Clock clock;

    PriceCache(Duration ttl, int maxEntries) {
        this(ttl, maxEntries, Clock.systemUTC());
    }

    PriceCache(Duration ttl, int maxEntries, Clock clock) {
        this.ttl = ttl;
        this.maxEntries = Math.max(1, maxEntries);
        this.clock = clock;
    }

    /** Not atomic per key on purpose: a lock held across a network call would let one slow source stall all. */
    List<ChargePointPrice> get(String key, Supplier<List<ChargePointPrice>> loader) {
        Entry cached = entries.get(key);
        Instant now = clock.instant();
        if (cached != null && cached.expiresAt().isAfter(now)) return cached.value();

        List<ChargePointPrice> loaded = loader.get();
        if (entries.size() >= maxEntries) {
            entries.values().removeIf(entry -> !entry.expiresAt().isAfter(now));
            if (entries.size() >= maxEntries) {
                log.debug("Price cache full at {} entries — clearing", entries.size());
                entries.clear();
            }
        }
        entries.put(key, new Entry(now.plus(ttl), loaded));
        return loaded;
    }

    /** Coordinates rounded to ~100 m, so neighbouring requests share an entry. */
    static String key(String source, GeoBounds bounds) {
        return source + "@" + Math.round(bounds.latMin() * 1_000) + "," + Math.round(bounds.lonMin() * 1_000)
                + ":" + Math.round(bounds.latMax() * 1_000) + "," + Math.round(bounds.lonMax() * 1_000);
    }
}
