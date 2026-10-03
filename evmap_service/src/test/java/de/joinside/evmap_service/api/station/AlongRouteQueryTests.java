package de.joinside.evmap_service.api.station;

import de.joinside.evmap_service.support.PostgisDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * "Stations along a route" against a real PostGIS (ADR 0017).
 * <p>
 * The route runs due east along 48.0° N from 8.0° to 12.0° E, about 297 km, with stations placed by
 * longitude so the expected driving order is readable off the test data. A latitude step of 0.01° is
 * about 1.1 km, which is how far off the road the "near" and "far" ones sit.
 */
class AlongRouteQueryTests {
    private static final double LAT = 48.0;

    private StationService service;
    private UUID start;
    private UUID middle;
    private UUID end;
    private UUID nearMiss;

    @BeforeEach
    void setUp() {
        PostgisDatabase.clearMasterData();
        service = new StationService(new StationSpatialRepository(PostgisDatabase.jdbc()),
                mock(ChargingStationRepository.class), mock(ChargingConnectorRepository.class), mock(StationSourceRepository.class));

        // Inserted out of driving order on purpose: the order of the answer is the query's doing.
        end = station("End", "IONITY", LAT + 0.01, 11.9, "CCS", "350.0");
        start = station("Start", "EnBW", LAT, 8.1, "TYPE_2", "22.0");
        middle = station("Middle", null, LAT - 0.02, 10.0, "CCS", "150.0");
        nearMiss = station("Near miss", "EnBW", LAT + 0.03, 9.0, "TYPE_2", "11.0");
        // 30 km north of the road, and far behind its end: neither is on the way.
        station("Off the road", "EnBW", LAT + 0.27, 10.0, "CCS", "150.0");
        station("Behind the end", "EnBW", LAT, 14.0, "CCS", "150.0");
    }

    private static UUID station(String name, String operator, double lat, double lon, String connector, String kw) {
        UUID id = PostgisDatabase.insertStation(name, operator, "DE", lat, lon);
        PostgisDatabase.insertConnector(id, connector, new BigDecimal(kw), 2);
        return id;
    }

    /** The straight road, with a point every 0.5°, which is more than the two a line needs. */
    private static List<StationController.RoutePoint> road() {
        List<StationController.RoutePoint> points = new ArrayList<>();
        for (double lon = 8.0; lon <= 12.0; lon += 0.5) points.add(new StationController.RoutePoint(LAT, lon));
        return points;
    }

    private static AlongRouteQuery along(double corridorKm) {
        return new AlongRouteQuery(road(), corridorKm, null, null, null, null, 200);
    }

    private List<UUID> ids(AlongRouteQuery query) {
        return service.alongRoute(query).stream().map(found -> found.station().id()).toList();
    }

    @Test
    @DisplayName("returns the stations inside the corridor in driving order, whatever order they were stored in")
    void listsInDrivingOrder() {
        assertThat(ids(along(5))).containsExactly(start, nearMiss, middle, end);
    }

    @Test
    @DisplayName("a superseded station is not offered along the route")
    void skipsSupersededStations() {
        PostgisDatabase.jdbc().sql("UPDATE master.charging_station SET superseded_by=:by WHERE id=:id")
                .param("by", middle).param("id", nearMiss).update();

        assertThat(ids(along(5))).containsExactly(start, middle, end);
    }

    @Test
    @DisplayName("measures how far along the route and how far off it each station is")
    void measuresPosition() {
        var found = service.alongRoute(along(5));

        // 1° of longitude at 48° N is about 74.5 km: the start station is 0.1° in, the end one 3.9°.
        assertThat(found.getFirst().distanceAlongRouteKm()).isBetween(6.5, 8.5);
        assertThat(found.getLast().distanceAlongRouteKm()).isBetween(285.0, 295.0);
        assertThat(found.getFirst().distanceToRouteKm()).isLessThan(0.1);
        assertThat(found).filteredOn(station -> station.station().id().equals(nearMiss))
                .singleElement().satisfies(station -> assertThat(station.distanceToRouteKm()).isBetween(3.0, 3.6));
        assertThat(found).extracting(StationController.RouteStation::distanceAlongRouteKm).isSorted();
    }

    @Test
    @DisplayName("the corridor decides which side roads are near enough")
    void corridorWidth() {
        // Middle sits 2.2 km and Near miss 3.3 km from the road; Off the road is 30 km away.
        assertThat(ids(along(1.5))).containsExactly(start, end);
        assertThat(ids(along(2.5))).containsExactly(start, middle, end);
        assertThat(ids(along(5))).containsExactly(start, nearMiss, middle, end);
        assertThat(ids(along(25))).containsExactly(start, nearMiss, middle, end);
    }

    @Test
    @DisplayName("applies the same connector, power and operator filters as the map")
    void filters() {
        assertThat(ids(new AlongRouteQuery(road(), 5, List.of("ccs"), null, null, null, 200))).containsExactly(middle, end);
        assertThat(ids(new AlongRouteQuery(road(), 5, null, new BigDecimal("100"), null, null, 200))).containsExactly(middle, end);
        assertThat(ids(new AlongRouteQuery(road(), 5, null, null, List.of("enbw"), null, 200))).containsExactly(middle, end);
        assertThat(ids(new AlongRouteQuery(road(), 5, null, null, null, List.of("IONITY"), 200))).containsExactly(end);
        // Empty lists mean "no filter", as in the map query.
        assertThat(ids(new AlongRouteQuery(road(), 5, List.of(), null, List.of(), List.of(), 200))).hasSize(4);
    }

    @Test
    @DisplayName("past the limit the strongest chargers are kept, still in driving order")
    void limitKeepsTheStrongest() {
        assertThat(ids(new AlongRouteQuery(road(), 5, null, null, null, null, 2))).containsExactly(middle, end);
    }

    @Test
    @DisplayName("finds stations along a long, bent route that a single bounding box would drown in")
    void bentRoute() {
        // Down the Rhine valley and then east: a diagonal route whose box covers far more than the road.
        PostgisDatabase.clearMasterData();
        UUID onTheRoad = station("On the road", "EnBW", 49.0, 8.5, "CCS", "150.0");
        station("Inside the box, off the road", "EnBW", 48.0, 8.0, "CCS", "150.0");
        List<StationController.RoutePoint> route = List.of(new StationController.RoutePoint(50.0, 8.0),
                new StationController.RoutePoint(49.0, 8.5), new StationController.RoutePoint(48.0, 11.0));

        assertThat(ids(new AlongRouteQuery(route, 3, null, null, null, null, 200))).containsExactly(onTheRoad);
    }

    @Test
    @DisplayName("rejects routes and corridors outside the promised bounds before touching the database")
    void rejectsOutOfBounds() {
        var two = List.of(new StationController.RoutePoint(48, 8), new StationController.RoutePoint(48, 9));
        assertThatThrownBy(() -> service.alongRoute(new AlongRouteQuery(null, 5, null, null, null, null, 10)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("route");
        assertThatThrownBy(() -> service.alongRoute(new AlongRouteQuery(two.subList(0, 1), 5, null, null, null, null, 10)))
                .hasMessageContaining("route");
        List<StationController.RoutePoint> tooLong = java.util.Collections.nCopies(StationService.MAX_ROUTE_POINTS + 1, two.getFirst());
        assertThatThrownBy(() -> service.alongRoute(new AlongRouteQuery(tooLong, 5, null, null, null, null, 10)))
                .hasMessageContaining("route");
        assertThatThrownBy(() -> service.alongRoute(new AlongRouteQuery(
                List.of(new StationController.RoutePoint(91, 8), two.getLast()), 5, null, null, null, null, 10)))
                .hasMessageContaining("latitudes");
        assertThatThrownBy(() -> service.alongRoute(new AlongRouteQuery(two, 0.01, null, null, null, null, 10)))
                .hasMessageContaining("corridorKm");
        assertThatThrownBy(() -> service.alongRoute(new AlongRouteQuery(two, 26, null, null, null, null, 10)))
                .hasMessageContaining("corridorKm");
        assertThatThrownBy(() -> service.alongRoute(new AlongRouteQuery(two, Double.NaN, null, null, null, null, 10)))
                .hasMessageContaining("corridorKm");
        assertThatThrownBy(() -> service.alongRoute(new AlongRouteQuery(two, 5, null, null, null, null, 201)))
                .hasMessageContaining("limit");
    }
}
