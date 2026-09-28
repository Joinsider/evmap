package de.joinside.evmap_service.availability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class AvailabilityCacheTests {
    private static final List<ChargePointAvailability> ANSWER =
            List.of(new ChargePointAvailability("DEX1", LiveAvailability.AVAILABLE, null));

    private static Supplier<List<ChargePointAvailability>> counting(AtomicInteger calls) {
        return () -> {
            calls.incrementAndGet();
            return ANSWER;
        };
    }

    @Test
    @DisplayName("reuses an answer within the TTL and asks again after it")
    void expiresAfterTtl() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        AvailabilityCache cache = new AvailabilityCache(Duration.ofMillis(50), 10);

        cache.get("a", counting(calls));
        cache.get("a", counting(calls));
        assertThat(calls).hasValue(1);

        Thread.sleep(80);
        assertThat(cache.get("a", counting(calls))).isSameAs(ANSWER);
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("a full cache drops expired entries first, and clears only when that is not enough")
    void evictsWhenFull() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        AvailabilityCache shortLived = new AvailabilityCache(Duration.ofMillis(20), 2);
        shortLived.get("a", counting(calls));
        shortLived.get("b", counting(calls));
        Thread.sleep(40);
        // Both expired: making room removes them without clearing anything still live.
        shortLived.get("c", counting(calls));
        assertThat(calls).hasValue(3);

        AvailabilityCache longLived = new AvailabilityCache(Duration.ofMinutes(1), 2);
        longLived.get("a", counting(calls));
        longLived.get("b", counting(calls));
        longLived.get("c", counting(calls));
        // Nothing had expired, so the cache was cleared wholesale and "a" is asked for again.
        longLived.get("a", counting(calls));
        assertThat(calls).hasValue(7);
    }

    @Test
    @DisplayName("keys round coordinates to ~100 m so a panning map reuses its answers")
    void roundsKeys() {
        String key = AvailabilityCache.key("MobiDataBW", new GeoBounds(48.77581, 9.18294, 48.78589, 9.19291));
        assertThat(key).isEqualTo("MobiDataBW@48776,9183:48786,9193");
        assertThat(AvailabilityCache.key("MobiDataBW", new GeoBounds(48.77579, 9.18296, 48.78591, 9.19289)))
                .isEqualTo(key);
    }
}
