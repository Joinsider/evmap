package de.joinside.evmap_service.availability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Short-lived in-process cache of provider answers, keyed by source and area.
 * <p>
 * Hand-rolled rather than pulled in: the project had no caching dependency at all, and what is needed
 * here is a map with a TTL measured in one minute. Adding Caffeine or Redis for that would be a
 * heavier decision than the feature warrants.
 * <p>
 * <strong>This is correct for exactly one API container.</strong> With two, each keeps its own copy
 * and polls independently — harmless for an unauthenticated, unmetered source like MobiData BW, and
 * wrong for a provider whose budget is per key rather than per process. Adding a second API replica
 * means moving this out of process first. Nothing will fail loudly when that day comes, which is why
 * it is written down here and in ADR 0015.
 */
class AvailabilityCache {
    private static final Logger log = LoggerFactory.getLogger(AvailabilityCache.class);

    private record Entry(Instant expiresAt, List<ChargePointAvailability> value) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final int maxEntries;

    AvailabilityCache(Duration ttl, int maxEntries) {
        this.ttl = ttl;
        this.maxEntries = Math.max(1, maxEntries);
    }

    /**
     * Returns the cached answer for {@code key}, or computes and stores one.
     * <p>
     * Deliberately not atomic per key: two concurrent misses may both call the loader, which costs a
     * duplicate upstream request and stores the same answer twice. The alternative — holding a lock
     * across a network call — lets one slow provider stall every request for an unrelated area, which
     * is the worse failure for a source that is allowed to be down.
     */
    List<ChargePointAvailability> get(String key, Supplier<List<ChargePointAvailability>> loader) {
        Entry cached = entries.get(key);
        Instant now = Instant.now();
        if (cached != null && cached.expiresAt().isAfter(now)) return cached.value();

        List<ChargePointAvailability> loaded = loader.get();
        evictIfFull();
        entries.put(key, new Entry(now.plus(ttl), loaded));
        return loaded;
    }

    private void evictIfFull() {
        if (entries.size() < maxEntries) return;
        Instant now = Instant.now();
        entries.values().removeIf(entry -> !entry.expiresAt().isAfter(now));
        if (entries.size() >= maxEntries) {
            log.debug("Availability cache full at {} live entries — clearing", entries.size());
            entries.clear();
        }
    }

    /**
     * Cache key for one provider's view of one area.
     * <p>
     * Coordinates are rounded to three decimals — roughly 100 m — so that the map's continuous camera
     * movement does not produce a distinct key per frame. Rounding outward rather than to nearest
     * would be needed if the answer were used to decide what is <em>inside</em> the box; it is not,
     * because matching is by identifier and a slightly wrong box only changes which identifiers were
     * offered.
     */
    static String key(String source, GeoBounds bounds) {
        return source + "@" + round(bounds.latMin()) + "," + round(bounds.lonMin())
                + ":" + round(bounds.latMax()) + "," + round(bounds.lonMax());
    }

    private static long round(double value) {
        return Math.round(value * 1_000);
    }
}
