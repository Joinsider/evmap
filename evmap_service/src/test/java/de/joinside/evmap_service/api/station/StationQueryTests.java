package de.joinside.evmap_service.api.station;

import de.joinside.evmap_service.support.PostgisDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The map and directory queries against a real PostGIS, through the service that validates them.
 * <p>
 * The filters are assembled into SQL conditionally, and an operator deny- and allowlist are two lists
 * of the same type that mean opposite things — both easy to get subtly wrong in a way only the
 * database would notice. The station detail's JPA side is mocked: it is plain entity lookup, and the
 * interesting part is the aggregation on top of it.
 */
class StationQueryTests {
    private static final double STUTTGART_LAT = 48.7758;
    private static final double STUTTGART_LON = 9.1829;

    private StationService service;
    private OperatorService operators;
    private final ChargingStationRepository stations = mock(ChargingStationRepository.class);
    private final ChargingConnectorRepository connectors = mock(ChargingConnectorRepository.class);
    private final StationSourceRepository sources = mock(StationSourceRepository.class);

    private UUID enbw;
    private UUID ionity;
    private UUID unnamed;

    @BeforeEach
    void setUp() {
        PostgisDatabase.clearMasterData();
        var jdbc = PostgisDatabase.jdbc();
        service = new StationService(new StationSpatialRepository(jdbc), stations, connectors, sources);
        operators = new OperatorService(new OperatorRepository(jdbc));

        enbw = PostgisDatabase.insertStation("EnBW Mitte", "EnBW mobility+", "DE", STUTTGART_LAT, STUTTGART_LON);
        PostgisDatabase.insertConnector(enbw, "TYPE_2", new BigDecimal("22.0"), 2);
        ionity = PostgisDatabase.insertStation("IONITY Nord", "IONITY", "DE", STUTTGART_LAT + 0.01, STUTTGART_LON);
        PostgisDatabase.insertConnector(ionity, "CCS", new BigDecimal("350.0"), 4);
        unnamed = PostgisDatabase.insertStation("Parkhaus", null, "DE", STUTTGART_LAT - 0.01, STUTTGART_LON);
        PostgisDatabase.insertConnector(unnamed, "TYPE_2", new BigDecimal("11.0"), 1);
        // Far outside every radius used below: Berlin.
        PostgisDatabase.insertStation("Berlin", "EnBW mobility+", "DE", 52.52, 13.405);
    }

    private static NearbyQuery around(int radiusKm) {
        return new NearbyQuery(STUTTGART_LAT, STUTTGART_LON, radiusKm, null, null, null, null, null, 100);
    }

    private List<UUID> ids(NearbyQuery query) {
        return service.nearby(query).stream().map(StationController.StationSummary::id).toList();
    }

    @Test
    @DisplayName("returns stations inside the radius, strongest first, with their maximum power")
    void findsNearbyByPower() {
        var results = service.nearby(around(5));

        assertThat(results).extracting(StationController.StationSummary::id).containsExactly(ionity, enbw, unnamed);
        assertThat(results.getFirst().maxPowerKw()).isEqualByComparingTo("350.0");
    }

    @Test
    @DisplayName("filters by connector type, case-insensitively, and by minimum power")
    void filtersByConnectorAndPower() {
        assertThat(ids(new NearbyQuery(STUTTGART_LAT, STUTTGART_LON, 5, List.of("type_2"), null, null, null, null, 100)))
                .containsExactlyInAnyOrder(enbw, unnamed);
        assertThat(ids(new NearbyQuery(STUTTGART_LAT, STUTTGART_LON, 5, null, new BigDecimal("20"), null, null, null, 100)))
                .containsExactlyInAnyOrder(ionity, enbw);
        // An empty list means "no filter", not "match nothing".
        assertThat(ids(new NearbyQuery(STUTTGART_LAT, STUTTGART_LON, 5, List.of(), null, null, null, null, 100)))
                .hasSize(3);
    }

    @Test
    @DisplayName("hidden networks are excluded in the query, and unnamed stations survive the denylist")
    void excludesHiddenOperators() {
        assertThat(ids(new NearbyQuery(STUTTGART_LAT, STUTTGART_LON, 5, null, null, null, List.of("enbw MOBILITY+"), null, 100)))
                .containsExactlyInAnyOrder(ionity, unnamed);
    }

    @Test
    @DisplayName("an allowlist keeps only the picked networks, which never includes an unnamed station")
    void keepsOnlyAllowedOperators() {
        assertThat(ids(new NearbyQuery(STUTTGART_LAT, STUTTGART_LON, 5, null, null, null, null, List.of("Ionity"), 100)))
                .containsExactly(ionity);
        assertThat(ids(new NearbyQuery(STUTTGART_LAT, STUTTGART_LON, 5, null, null, "EnBW mobility+", null, null, 100)))
                .containsExactly(enbw);
    }

    @Test
    @DisplayName("the row limit keeps the strongest stations, not an arbitrary slice")
    void limitsToTheStrongest() {
        assertThat(ids(new NearbyQuery(STUTTGART_LAT, STUTTGART_LON, 5, null, null, null, null, null, 1)))
                .containsExactly(ionity);
    }

    @Test
    @DisplayName("rejects queries outside the promised bounds before touching the database")
    void rejectsOutOfBoundsQueries() {
        assertThatThrownBy(() -> service.nearby(around(0))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("radiusKm");
        assertThatThrownBy(() -> service.nearby(around(1_001))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.nearby(new NearbyQuery(0, 0, 5, null, null, null, null, null, 0)))
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> service.nearby(new NearbyQuery(0, 0, 5, null, null, null, null, null, 2_001)))
                .hasMessageContaining("limit");

        List<String> tooMany = java.util.stream.IntStream.rangeClosed(0, StationService.MAX_EXCLUDED_OPERATORS)
                .mapToObj(i -> "Operator " + i).toList();
        assertThatThrownBy(() -> service.nearby(new NearbyQuery(0, 0, 5, null, null, null, tooMany, null, 10)))
                .hasMessageContaining("excludeOperator");
        assertThatThrownBy(() -> service.nearby(new NearbyQuery(0, 0, 5, null, null, null, null, tooMany, 10)))
                .hasMessageContaining("includeOperator");
    }

    @Test
    @DisplayName("the detail sums connector rows per type and power and lists each source once")
    void aggregatesDetail() {
        ChargingStation station = new ChargingStation();
        station.id = enbw;
        station.displayName = "EnBW Mitte";
        station.countryCode = "DE";
        when(stations.findById(enbw)).thenReturn(Optional.of(station));
        when(connectors.findByStationId(enbw)).thenReturn(List.of(
                connector("TYPE_2", "22.0", 1), connector("TYPE_2", "22.0", 1), connector("CCS", "150.0", 2),
                connector("SCHUKO", null, 1)));
        when(sources.findByStationIdOrderBySource(enbw)).thenReturn(List.of(source("BNetzA"), source("OCM"), source("OCM")));

        var detail = service.detail(enbw);

        assertThat(detail.connectors()).containsExactly(
                new StationController.Connector("TYPE_2", new BigDecimal("22.0"), 2),
                new StationController.Connector("CCS", new BigDecimal("150.0"), 2),
                new StationController.Connector("SCHUKO", null, 1));
        assertThat(detail.station().maxPowerKw()).isEqualByComparingTo("150.0");
        assertThat(detail.sources()).containsExactly("BNetzA", "OCM");
    }

    @Test
    @DisplayName("an unknown station id is a not-found, not an empty detail")
    void unknownDetail() {
        UUID missing = UUID.randomUUID();
        when(stations.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(missing)).isInstanceOf(StationController.StationNotFoundException.class);
    }

    @Test
    @DisplayName("a station bundled from several operators matches any of them and is hidden only when all are")
    void bundledStationsMatchEveryOperator() {
        // An IONITY station that also holds a charge point of Partner AG, as bundled Spanish and Swiss sites do.
        UUID partnerPoint = PostgisDatabase.insertChargePoint(ionity, "p1", null);
        PostgisDatabase.jdbc().sql("UPDATE master.charge_point SET operator_name='Partner AG' WHERE id=:id")
                .param("id", partnerPoint).update();

        assertThat(ids(new NearbyQuery(STUTTGART_LAT, STUTTGART_LON, 5, null, null, null, null, List.of("partner ag"), 100)))
                .containsExactly(ionity);
        assertThat(ids(new NearbyQuery(STUTTGART_LAT, STUTTGART_LON, 5, null, null, "Partner AG", null, null, 100)))
                .containsExactly(ionity);
        assertThat(ids(new NearbyQuery(STUTTGART_LAT, STUTTGART_LON, 5, null, null, null, List.of("IONITY"), null, 100)))
                .contains(ionity);
        assertThat(ids(new NearbyQuery(STUTTGART_LAT, STUTTGART_LON, 5, null, null, null, List.of("IONITY", "Partner AG"), null, 100)))
                .doesNotContain(ionity);
        assertThat(operators.search("partner", 10)).containsExactly(new OperatorController.Operator("Partner AG", 1));
        assertThat(operators.search("ionity", 10)).containsExactly(new OperatorController.Operator("IONITY", 1));
    }

    @Test
    @DisplayName("the operator directory counts stations per network, largest first, and escapes LIKE wildcards")
    void searchesOperators() {
        assertThat(operators.search("", 10)).containsExactly(
                new OperatorController.Operator("EnBW mobility+", 2),
                new OperatorController.Operator("IONITY", 1));
        assertThat(operators.search("ion", 10)).extracting(OperatorController.Operator::name).containsExactly("IONITY");
        // "%" is a literal here, so it matches nothing rather than everything.
        assertThat(operators.search("%", 10)).isEmpty();
        assertThatThrownBy(() -> operators.search("x", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a superseded station is left off the map and out of the operator directory")
    void skipsSupersededStations() {
        PostgisDatabase.jdbc().sql("UPDATE master.charging_station SET superseded_by=:by WHERE id=:id")
                .param("by", ionity).param("id", enbw).update();

        assertThat(ids(around(5))).containsExactly(ionity, unnamed);
        // The Berlin station keeps EnBW in the directory, now with one station instead of two.
        assertThat(operators.search("enbw", 10)).containsExactly(new OperatorController.Operator("EnBW mobility+", 1));
    }

    private static ChargingConnector connector(String type, String powerKw, int quantity) {
        ChargingConnector connector = new ChargingConnector();
        connector.connectorType = type;
        connector.powerKw = powerKw == null ? null : new BigDecimal(powerKw);
        connector.quantity = quantity;
        return connector;
    }

    private static StationSource source(String name) {
        StationSource source = new StationSource();
        source.source = name;
        return source;
    }
}
