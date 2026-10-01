package de.joinside.evmap_service.api.station;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The part of "stations along a route" that needs no database: what the controller fills in, what the service
 * refuses before it costs a round trip, and how it treats a slow query. The corridor SQL itself is in
 * {@link AlongRouteQueryTests}.
 */
class AlongRouteServiceTests {
    private static final StationController.RoutePoint A = new StationController.RoutePoint(48.0, 8.0);
    private static final StationController.RoutePoint B = new StationController.RoutePoint(48.0, 9.0);

    private final StationSpatialRepository spatial = mock(StationSpatialRepository.class);
    private final StationService service = new StationService(spatial, mock(ChargingStationRepository.class),
            mock(ChargingConnectorRepository.class), mock(StationSourceRepository.class));

    private static AlongRouteQuery query(List<StationController.RoutePoint> route, double corridorKm, int limit) {
        return new AlongRouteQuery(route, corridorKm, null, null, null, null, limit);
    }

    // --- controller -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the controller defaults the corridor to 5 km and the limit to 200, and passes every filter on")
    void controllerDefaults() {
        var stations = mock(StationService.class);
        var controller = new StationController(stations);
        var answer = List.of(new StationController.RouteStation(null, 1.5, 0.25));
        when(stations.alongRoute(any())).thenReturn(answer);

        var bare = controller.alongRoute(new StationController.AlongRouteRequest(List.of(A, B), null, null, null, null, null, null));
        assertThat(bare).isSameAs(answer);

        var captor = ArgumentCaptor.forClass(AlongRouteQuery.class);
        verify(stations).alongRoute(captor.capture());
        assertThat(captor.getValue().corridorKm()).isEqualTo(5.0);
        assertThat(captor.getValue().limit()).isEqualTo(StationService.MAX_ALONG_ROUTE_LIMIT);
        assertThat(captor.getValue().route()).containsExactly(A, B);
    }

    @Test
    @DisplayName("what the client sends is what the service is asked")
    void controllerPassesTheRequestOn() {
        var stations = mock(StationService.class);
        var controller = new StationController(stations);

        controller.alongRoute(new StationController.AlongRouteRequest(List.of(A, B), 12.5, List.of("CCS"), new BigDecimal("50"),
                List.of("Tesla"), List.of("EnBW"), 30));

        var captor = ArgumentCaptor.forClass(AlongRouteQuery.class);
        verify(stations).alongRoute(captor.capture());
        var sent = captor.getValue();
        assertThat(sent.corridorKm()).isEqualTo(12.5);
        assertThat(sent.limit()).isEqualTo(30);
        assertThat(sent.connectorTypes()).containsExactly("CCS");
        assertThat(sent.minPowerKw()).isEqualByComparingTo("50");
        assertThat(sent.excludeOperators()).containsExactly("Tesla");
        assertThat(sent.includeOperators()).containsExactly("EnBW");
    }

    // --- service ----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a valid query reaches the database once, with empty lists read as no filter")
    void validQuery() {
        var found = List.of(new StationController.RouteStation(null, 10, 1));
        when(spatial.findAlongRoute(any())).thenReturn(found);

        var result = service.alongRoute(new AlongRouteQuery(List.of(A, B), 5, List.of(), null, List.of(), List.of(), 10));

        assertThat(result).isSameAs(found);
        var captor = ArgumentCaptor.forClass(AlongRouteQuery.class);
        verify(spatial).findAlongRoute(captor.capture());
        assertThat(captor.getValue().connectorTypes()).isNull();
        assertThat(captor.getValue().excludeOperators()).isNull();
    }

    @Test
    @DisplayName("a query slower than a second is answered all the same")
    void slowQuery() {
        when(spatial.findAlongRoute(any())).thenAnswer(invocation -> {
            Thread.sleep(StationService.SLOW_QUERY_MS + 50);
            return List.of();
        });

        assertThat(service.alongRoute(query(List.of(A, B), 5, 10))).isEmpty();
    }

    @Test
    @DisplayName("routes that are missing, too short, too long or contain a bad point are refused before the database")
    void refusesBadRoutes() {
        assertThatThrownBy(() -> service.alongRoute(query(null, 5, 10))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("route");
        assertThatThrownBy(() -> service.alongRoute(query(List.of(A), 5, 10))).hasMessageContaining("route");
        var tooLong = new ArrayList<StationController.RoutePoint>();
        IntStream.range(0, StationService.MAX_ROUTE_POINTS + 1).forEach(i -> tooLong.add(A));
        assertThatThrownBy(() -> service.alongRoute(query(tooLong, 5, 10))).hasMessageContaining("route");

        for (var bad : Arrays.asList(null, new StationController.RoutePoint(Double.NaN, 8), new StationController.RoutePoint(48, Double.POSITIVE_INFINITY),
                new StationController.RoutePoint(-90.5, 8), new StationController.RoutePoint(48, 181))) {
            assertThatThrownBy(() -> service.alongRoute(query(Arrays.asList(A, bad), 5, 10)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("latitudes");
        }
        verifyNoInteractions(spatial);
    }

    @Test
    @DisplayName("corridors, limits and operator lists outside the promised bounds are refused")
    void refusesBadBounds() {
        for (double corridor : new double[]{Double.NaN, 0.01, 25.5, -1})
            assertThatThrownBy(() -> service.alongRoute(query(List.of(A, B), corridor, 10))).hasMessageContaining("corridorKm");
        assertThatThrownBy(() -> service.alongRoute(query(List.of(A, B), 5, 0))).hasMessageContaining("limit");
        assertThatThrownBy(() -> service.alongRoute(query(List.of(A, B), 5, StationService.MAX_ALONG_ROUTE_LIMIT + 1))).hasMessageContaining("limit");

        List<String> tooMany = IntStream.rangeClosed(0, StationService.MAX_EXCLUDED_OPERATORS).mapToObj(i -> "Operator " + i).toList();
        assertThatThrownBy(() -> service.alongRoute(new AlongRouteQuery(List.of(A, B), 5, null, null, tooMany, null, 10)))
                .hasMessageContaining("excludeOperator");
        assertThatThrownBy(() -> service.alongRoute(new AlongRouteQuery(List.of(A, B), 5, null, null, null, tooMany, 10)))
                .hasMessageContaining("includeOperator");
        verifyNoInteractions(spatial);
    }

    @Test
    @DisplayName("the bounds themselves are allowed")
    void boundsAreInclusive() {
        when(spatial.findAlongRoute(any())).thenReturn(List.of());
        service.alongRoute(query(List.of(new StationController.RoutePoint(-90, -180), new StationController.RoutePoint(90, 180)),
                StationService.MIN_CORRIDOR_KM, 1));
        service.alongRoute(query(List.of(A, B), StationService.MAX_CORRIDOR_KM, StationService.MAX_ALONG_ROUTE_LIMIT));
        verify(spatial, org.mockito.Mockito.times(2)).findAlongRoute(any());
    }
}
