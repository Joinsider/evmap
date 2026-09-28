package de.joinside.evmap_service.api.station;

import de.joinside.evmap_service.availability.AvailabilityService;
import de.joinside.evmap_service.availability.GeoBounds;
import de.joinside.evmap_service.availability.LiveAvailability;
import de.joinside.evmap_service.availability.StationAvailability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/stations/availability} and {@code GET /api/v1/stations/{id}} overlap: the
 * viewport path would be a perfectly good UUID-shaped path variable if the framework preferred the
 * template. It does not — a literal segment outranks a variable — but that is a framework guarantee
 * this code depends on rather than states, and getting it wrong would surface as the map's live
 * request 400-ing on an unparseable UUID.
 */
class AvailabilityRoutingTests {

    private static final UUID STATION_ID = UUID.randomUUID();

    /** Mocked rather than subclassed: the service's constructor is package-private to its own package. */
    private final AvailabilityService availability = org.mockito.Mockito.mock(AvailabilityService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AvailabilityController(availability))
            .build();

    @Test
    @DisplayName("the viewport path is not swallowed by the station-id route")
    void viewportPathWins() throws Exception {
        org.mockito.Mockito.when(availability.inBounds(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new StationAvailability(STATION_ID, LiveAvailability.OCCUPIED,
                        0, 2, 0, 0, null, List.of(), List.of())));

        mockMvc.perform(get("/api/v1/stations/availability")
                        .param("latMin", "48.77").param("lonMin", "9.17")
                        .param("latMax", "48.78").param("lonMax", "9.19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value(LiveAvailability.OCCUPIED));
    }

    @Test
    @DisplayName("a station's own availability still resolves under its id")
    void stationPathResolves() throws Exception {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.when(availability.forStation(id))
                .thenReturn(Optional.of(new StationAvailability(id, LiveAvailability.AVAILABLE,
                        1, 0, 0, 0, null, List.of(), List.of())));

        mockMvc.perform(get("/api/v1/stations/{id}/availability", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stationId").value(id.toString()))
                .andExpect(jsonPath("$.status").value(LiveAvailability.AVAILABLE));
    }

    @Test
    @DisplayName("an inverted viewport is rejected rather than silently answered")
    void rejectsInvertedBounds() {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new GeoBounds(48.78, 9.17, 48.77, 9.19)))
                .hasMessageContaining("inverted");
    }
}
