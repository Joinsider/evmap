package de.joinside.evmap_service.availability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationsTests {
    private static final Instant EARLIER = Instant.parse("2026-10-02T08:00:00Z");
    private static final Instant LATER = EARLIER.plusSeconds(60);

    private static ChargePointAvailability at(Instant observedAt) {
        return new ChargePointAvailability("DEABCE1", LiveAvailability.AVAILABLE, observedAt);
    }

    private static ChargePointAvailability newer(ChargePointAvailability held, ChargePointAvailability candidate) {
        return Observations.newer(held, candidate, ChargePointAvailability::observedAt);
    }

    @Test
    @DisplayName("the later observation wins, whichever side it is on")
    void laterWins() {
        ChargePointAvailability earlier = at(EARLIER);
        ChargePointAvailability later = at(LATER);

        assertThat(newer(earlier, later)).isSameAs(later);
        assertThat(newer(later, earlier)).isSameAs(later);
    }

    @Test
    @DisplayName("on a tie the report already held stays")
    void tieKeepsHeld() {
        ChargePointAvailability held = at(EARLIER);

        assertThat(newer(held, at(EARLIER))).isSameAs(held);
    }

    @Test
    @DisplayName("a report without a timestamp loses to one with, and two without keep the held one")
    void missingTimestampLoses() {
        ChargePointAvailability dated = at(EARLIER);
        ChargePointAvailability undated = at(null);
        ChargePointAvailability otherUndated = at(null);

        assertThat(newer(dated, undated)).isSameAs(dated);
        assertThat(newer(undated, dated)).isSameAs(dated);
        assertThat(newer(undated, otherUndated)).isSameAs(undated);
    }
}
