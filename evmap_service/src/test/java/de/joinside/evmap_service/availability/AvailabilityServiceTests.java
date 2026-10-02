package de.joinside.evmap_service.availability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AvailabilityServiceTests {
    private static final UUID STATION = UUID.randomUUID();
    private static final Instant OBSERVED = Instant.parse("2026-09-28T11:50:00Z");

    /** A provider shaped like the French one: it answers with far more than the area holds. */
    private static final class WholeCountryProvider implements AvailabilityProvider {
        @Override
        public String source() {
            return "WholeCountry";
        }

        @Override
        public Attribution attribution() {
            return new Attribution("Whole Country", "Licence Ouverte 2.0", "https://example.org");
        }

        @Override
        public boolean covers(String countryCode) {
            return "FR".equals(countryCode);
        }

        @Override
        public List<ChargePointAvailability> fetch(GeoBounds bounds) {
            return List.of(new ChargePointAvailability("FRAAA1", LiveAvailability.AVAILABLE, OBSERVED),
                    new ChargePointAvailability("FRBBB2", LiveAvailability.OCCUPIED, OBSERVED),
                    new ChargePointAvailability("FRELSEWHERE9", LiveAvailability.OUT_OF_ORDER, OBSERVED));
        }
    }

    private static AvailabilityService service(ChargePointDirectory directory) {
        AvailabilityProperties properties = new AvailabilityProperties(true, Duration.ofSeconds(60), 500, 300, 1.5, 5000);
        return new AvailabilityService(List.of(new WholeCountryProvider()), directory, properties);
    }

    private static ChargePointDirectory.KnownChargePoint chargePoint(String evseId) {
        return new ChargePointDirectory.KnownChargePoint(UUID.randomUUID(), STATION, evseId, evseId);
    }

    @Test
    @DisplayName("resolves only the station's own charge points out of a country-wide answer")
    void keepsOnlyRequestedIdentifiers() {
        ChargePointDirectory directory = mock(ChargePointDirectory.class);
        when(directory.location(STATION)).thenReturn(Optional.of(
                new ChargePointDirectory.StationLocation(STATION, 48.85, 2.35, "FR")));
        when(directory.forStation(STATION)).thenReturn(List.of(chargePoint("FRAAA1"), chargePoint("FRBBB2"),
                new ChargePointDirectory.KnownChargePoint(UUID.randomUUID(), STATION, null, null)));

        StationAvailability answer = service(directory).forStation(STATION).orElseThrow();

        assertThat(answer.status()).isEqualTo(LiveAvailability.AVAILABLE);
        assertThat(answer.available()).isEqualTo(1);
        assertThat(answer.occupied()).isEqualTo(1);
        // FRELSEWHERE9 belongs to some other station and must not be counted here.
        assertThat(answer.outOfOrder()).isZero();
        assertThat(answer.unknown()).isEqualTo(1);
        assertThat(answer.observedAt()).isEqualTo(OBSERVED);
        // Credited once per station and per resolved charge point; the unresolved one names nobody.
        assertThat(answer.sources()).extracting(Attribution::name).containsExactly("Whole Country");
        assertThat(answer.chargePoints()).extracting(StationAvailability.ChargePointStatus::source)
                .containsExactly("Whole Country", "Whole Country", null);
    }

    @Test
    @DisplayName("a viewport answers only for stations inside it, whatever the provider returned")
    void viewportIgnoresForeignIdentifiers() {
        ChargePointDirectory directory = mock(ChargePointDirectory.class);
        GeoBounds bounds = new GeoBounds(48.85, 2.34, 48.86, 2.36);
        when(directory.inBounds(any(), anyInt())).thenReturn(List.of(chargePoint("FRBBB2")));
        when(directory.countriesInBounds(any())).thenReturn(List.of("FR"));

        assertThat(service(directory).inBounds(bounds)).singleElement()
                .satisfies(station -> {
                    assertThat(station.status()).isEqualTo(LiveAvailability.OCCUPIED);
                    assertThat(station.outOfOrder()).isZero();
                });
    }

    /** A provider for Germany that answers one charge point with a fixed status and time. */
    private record FixedProvider(String source, Attribution attribution, ChargePointAvailability answer)
            implements AvailabilityProvider {
        @Override
        public boolean covers(String countryCode) {
            return "DE".equals(countryCode);
        }

        @Override
        public List<ChargePointAvailability> fetch(GeoBounds bounds) {
            return List.of(answer);
        }
    }

    @Test
    @DisplayName("where two providers report one charge point, the newer observation wins, whichever is asked first")
    void newerObservationWins() {
        ChargePointDirectory directory = mock(ChargePointDirectory.class);
        when(directory.location(STATION)).thenReturn(Optional.of(
                new ChargePointDirectory.StationLocation(STATION, 48.77, 9.18, "DE")));
        when(directory.forStation(STATION)).thenReturn(List.of(chargePoint("DEEBWE1")));
        Attribution relay = new Attribution("Relay", "dl-de/by-2.0", "https://relay.example");
        Attribution operator = new Attribution("Operator via Mobilithek", "CC BY 4.0", "https://operator.example");
        AvailabilityProvider older = new FixedProvider("Relay", relay,
                new ChargePointAvailability("DEEBWE1", LiveAvailability.AVAILABLE, OBSERVED));
        // Credited per entry, not with the provider's own attribution.
        AvailabilityProvider newer = new FixedProvider("Direct", new Attribution("Platform", "-", "https://platform.example"),
                new ChargePointAvailability("DEEBWE1", LiveAvailability.OCCUPIED, OBSERVED.plusSeconds(60), operator));
        AvailabilityProperties properties = new AvailabilityProperties(true, Duration.ofSeconds(60), 500, 300, 1.5, 5000);

        for (List<AvailabilityProvider> order : List.of(List.of(older, newer), List.of(newer, older))) {
            StationAvailability answer = new AvailabilityService(order, directory, properties).forStation(STATION).orElseThrow();

            assertThat(answer.status()).isEqualTo(LiveAvailability.OCCUPIED);
            assertThat(answer.sources()).containsExactly(operator);
            assertThat(answer.chargePoints()).extracting(StationAvailability.ChargePointStatus::source)
                    .containsExactly("Operator via Mobilithek");
        }
    }

    @Test
    @DisplayName("a provider that throws costs its own answer only; entries without an id are skipped")
    void containsProviderFailure() {
        ChargePointDirectory directory = mock(ChargePointDirectory.class);
        when(directory.location(STATION)).thenReturn(Optional.of(
                new ChargePointDirectory.StationLocation(STATION, 48.77, 9.18, "DE")));
        when(directory.forStation(STATION)).thenReturn(List.of(chargePoint("DEEBWE1")));
        AvailabilityProvider throwing = new AvailabilityProvider() {
            @Override
            public String source() {
                return "Throwing";
            }

            @Override
            public Attribution attribution() {
                return new Attribution("Throwing", "-", "https://throwing.example");
            }

            @Override
            public boolean covers(String countryCode) {
                return true;
            }

            @Override
            public List<ChargePointAvailability> fetch(GeoBounds bounds) {
                throw new IllegalStateException("provider bug");
            }
        };
        AvailabilityProvider withoutId = new FixedProvider("NoId", new Attribution("NoId", "-", "https://noid.example"),
                new ChargePointAvailability(null, LiveAvailability.AVAILABLE, OBSERVED));
        AvailabilityProvider working = new FixedProvider("Working", new Attribution("Working", "-", "https://working.example"),
                new ChargePointAvailability("DEEBWE1", LiveAvailability.OCCUPIED, OBSERVED));
        AvailabilityProperties properties = new AvailabilityProperties(true, Duration.ofSeconds(60), 500, 300, 1.5, 5000);

        StationAvailability answer = new AvailabilityService(List.of(throwing, withoutId, working), directory, properties)
                .forStation(STATION).orElseThrow();

        assertThat(answer.status()).isEqualTo(LiveAvailability.OCCUPIED);
        assertThat(answer.sources()).extracting(Attribution::name).containsExactly("Working");
    }
}
