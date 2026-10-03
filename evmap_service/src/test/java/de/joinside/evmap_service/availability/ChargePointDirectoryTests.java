package de.joinside.evmap_service.availability;

import de.joinside.evmap_service.support.PostgisDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The read side of the charge point inventory against a real PostGIS, and the service on top of it.
 * <p>
 * This is where the join between the two data axes happens, so it is tested end to end: stored
 * EVSE-IDs in, a provider's report in, and only exact matches out.
 */
class ChargePointDirectoryTests {
    private static final Instant OBSERVED = Instant.parse("2026-09-28T11:50:00Z");
    private static final GeoBounds STUTTGART = new GeoBounds(48.7, 9.1, 48.9, 9.3);

    private ChargePointDirectory directory;
    private UUID resolvable;
    private UUID withoutEvseIds;

    /** Answers for Germany with two known EVSE-IDs and one nobody stores. */
    private static final class GermanProvider implements AvailabilityProvider {
        private int calls;

        @Override
        public String source() {
            return "German";
        }

        @Override
        public Attribution attribution() {
            return new Attribution("MobiData BW", "dl-de/by-2.0", "https://www.mobidata-bw.de");
        }

        @Override
        public boolean covers(String countryCode) {
            return "DE".equals(countryCode);
        }

        @Override
        public List<ChargePointAvailability> fetch(GeoBounds bounds) {
            calls++;
            return List.of(new ChargePointAvailability("DEEBWE9123161", LiveAvailability.AVAILABLE, OBSERVED),
                    new ChargePointAvailability("DEEBWE9123162", LiveAvailability.OCCUPIED, OBSERVED),
                    new ChargePointAvailability("DEUNKNOWN1", LiveAvailability.OUT_OF_ORDER, OBSERVED));
        }
    }

    @BeforeEach
    void setUp() {
        PostgisDatabase.clearMasterData();
        directory = new ChargePointDirectory(PostgisDatabase.jdbc());

        resolvable = PostgisDatabase.insertStation("EnBW Mitte", "EnBW", "DE", 48.7758, 9.1829);
        PostgisDatabase.insertChargePoint(resolvable, "1*1", "DE*EBW*E912316*1");
        PostgisDatabase.insertChargePoint(resolvable, "1*2", "DE*EBW*E912316*2");
        PostgisDatabase.insertChargePoint(resolvable, "1*3", null);

        withoutEvseIds = PostgisDatabase.insertStation("Parkhaus", null, "DE", 48.78, 9.18);
        PostgisDatabase.insertChargePoint(withoutEvseIds, "2*1", null);
    }

    private AvailabilityService service(AvailabilityProvider provider, double maxViewportSpan) {
        var properties = new AvailabilityProperties(true, Duration.ofSeconds(60), 500, 300, maxViewportSpan, 5000);
        return new AvailabilityService(List.of(provider), directory, properties);
    }

    @Test
    @DisplayName("reads a station's location and every charge point, including those without an EVSE-ID")
    void readsTheInventory() {
        assertThat(directory.location(resolvable)).hasValueSatisfying(location -> {
            assertThat(location.countryCode()).isEqualTo("DE");
            assertThat(location.latitude()).isEqualTo(48.7758);
        });
        assertThat(directory.location(UUID.randomUUID())).isEmpty();

        assertThat(directory.forStation(resolvable))
                .extracting(ChargePointDirectory.KnownChargePoint::evseIdNormalized)
                .containsExactly("DEEBWE9123161", "DEEBWE9123162", null);
    }

    @Test
    @DisplayName("a country's inventory counts every charge point and collects the distinct EVSE-IDs")
    void readsACountry() {
        UUID french = PostgisDatabase.insertStation("Lyon", null, "FR", 45.76, 4.83);
        PostgisDatabase.insertChargePoint(french, "3*1", "FR*ABC*E1");

        StoredChargePoints.Inventory germany = directory.inCountries(List.of("DE"));

        assertThat(germany.chargePoints()).isEqualTo(4);
        assertThat(germany.evseIds()).containsExactlyInAnyOrder("DEEBWE9123161", "DEEBWE9123162");
        assertThat(directory.inCountries(List.of("DE", "FR")).evseIds()).hasSize(3);
        assertThat(directory.inCountries(List.of())).isEqualTo(new StoredChargePoints.Inventory(0, Set.of()));
    }

    @Test
    @DisplayName("a viewport lists only resolvable charge points, and the countries it touches")
    void readsAViewport() {
        assertThat(directory.inBounds(STUTTGART, 100))
                .extracting(ChargePointDirectory.KnownChargePoint::stationId)
                .containsOnly(resolvable)
                .hasSize(2);
        assertThat(directory.inBounds(STUTTGART, 1)).hasSize(1);
        assertThat(directory.countriesInBounds(STUTTGART)).containsExactly("DE");
        assertThat(directory.countriesInBounds(new GeoBounds(0, 0, 1, 1))).isEmpty();
    }

    @Test
    @DisplayName("resolves a station's live status by exact EVSE-ID and credits the source")
    void resolvesAStation() {
        var answer = service(new GermanProvider(), 1.5).forStation(resolvable).orElseThrow();

        assertThat(answer.status()).isEqualTo(LiveAvailability.AVAILABLE);
        assertThat(answer.available()).isOne();
        assertThat(answer.occupied()).isOne();
        assertThat(answer.unknown()).isOne();
        assertThat(answer.sources()).extracting(Attribution::name).containsExactly("MobiData BW");
    }

    @Test
    @DisplayName("a station whose charge points publish no EVSE-ID is unknown without asking any provider")
    void skipsUnresolvableStations() {
        GermanProvider provider = new GermanProvider();

        var answer = service(provider, 1.5).forStation(withoutEvseIds).orElseThrow();

        assertThat(answer.isKnown()).isFalse();
        assertThat(answer.unknown()).isOne();
        assertThat(provider.calls).isZero();
        assertThat(service(provider, 1.5).forStation(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("a viewport answers only stations with a live status, and caches the provider's answer")
    void answersAViewport() {
        GermanProvider provider = new GermanProvider();
        AvailabilityService service = service(provider, 1.5);

        assertThat(service.inBounds(STUTTGART)).singleElement().satisfies(station -> {
            assertThat(station.stationId()).isEqualTo(resolvable);
            assertThat(station.chargePoints()).isEmpty();
        });
        service.inBounds(STUTTGART);
        assertThat(provider.calls).isOne();
    }

    @Test
    @DisplayName("a viewport wider than the span, or without charge points, answers empty")
    void refusesOversizedOrEmptyViewports() {
        GermanProvider provider = new GermanProvider();

        assertThat(service(provider, 0.1).inBounds(STUTTGART)).isEmpty();
        assertThat(service(provider, 1.5).inBounds(new GeoBounds(0, 0, 1, 1))).isEmpty();
        assertThat(provider.calls).isZero();
    }
}
