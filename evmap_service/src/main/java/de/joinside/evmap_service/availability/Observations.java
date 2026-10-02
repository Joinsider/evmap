package de.joinside.evmap_service.availability;

import java.time.Instant;
import java.util.function.Function;

/**
 * The one rule for two reports about the same charge point: the newer observation wins.
 * <p>
 * Applied between providers ({@link AvailabilityService}), between the feeds of one provider, and between two
 * mentions in one package — written once so the three cannot drift apart. A report without a timestamp loses to
 * one with; on a tie the report already held stays, so the outcome does not depend on hash or arrival order.
 */
public final class Observations {

    private Observations() {
    }

    public static <T> T newer(T held, T candidate, Function<T, Instant> observedAt) {
        Instant candidateAt = observedAt.apply(candidate);
        if (candidateAt == null) return held;
        Instant heldAt = observedAt.apply(held);
        return heldAt == null || candidateAt.isAfter(heldAt) ? candidate : held;
    }
}
