package de.joinside.evmap_service.api.station;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire format of {@code POST /api/v1/stations/along-route}, which the iOS client writes and reads
 * (`AlongRouteRequest` and `RouteStation` in `Features/Routing`). Like the availability payload, nothing
 * else notices a renamed property until the app stops decoding.
 */
class AlongRoutePayloadTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withPropertyValues("spring.jackson.default-property-inclusion=non_null");

    @Test
    @DisplayName("reads the request the client sends, with the filters named as on the map query")
    void readsTheRequest() {
        runner.run(context -> {
            var request = context.getBean(ObjectMapper.class).readValue("""
                    {"route":[{"latitude":48.0,"longitude":8.0},{"latitude":48.1,"longitude":9.0}],
                     "corridorKm":7.5,"connectorType":["CCS"],"minPowerKw":50,
                     "excludeOperator":["IONITY"],"includeOperator":["EnBW"],"limit":50}""",
                    StationController.AlongRouteRequest.class);

            assertThat(request.route()).containsExactly(new StationController.RoutePoint(48.0, 8.0),
                    new StationController.RoutePoint(48.1, 9.0));
            assertThat(request.corridorKm()).isEqualTo(7.5);
            assertThat(request.connectorType()).containsExactly("CCS");
            assertThat(request.minPowerKw()).isEqualByComparingTo("50");
            assertThat(request.excludeOperator()).containsExactly("IONITY");
            assertThat(request.includeOperator()).containsExactly("EnBW");
            assertThat(request.limit()).isEqualTo(50);
        });
    }

    @Test
    @DisplayName("treats every filter as optional, so a bare route is a valid request")
    void readsABareRoute() {
        runner.run(context -> {
            var request = context.getBean(ObjectMapper.class).readValue(
                    "{\"route\":[{\"latitude\":48.0,\"longitude\":8.0},{\"latitude\":48.1,\"longitude\":9.0}]}",
                    StationController.AlongRouteRequest.class);

            assertThat(request.corridorKm()).isNull();
            assertThat(request.limit()).isNull();
            assertThat(request.connectorType()).isNull();
        });
    }

    @Test
    @DisplayName("writes the station summary with its position along and distance to the route")
    void writesTheResponse() {
        runner.run(context -> {
            var station = new StationController.StationSummary(UUID.fromString("6c8b4e1e-6c2c-4e9a-9b77-9f0a2e7b1c31"),
                    "EnBW Mitte", "Hauptstraße 1", "Stuttgart", "70173", "DE", "EnBW", 48.0, 9.0, "OPERATIONAL",
                    new BigDecimal("150.0"));
            String json = context.getBean(ObjectMapper.class)
                    .writeValueAsString(new StationController.RouteStation(station, 42.5, 1.25));

            assertThat(json).contains("\"station\":{\"id\":\"6c8b4e1e-6c2c-4e9a-9b77-9f0a2e7b1c31\"")
                    .contains("\"distanceAlongRouteKm\":42.5")
                    .contains("\"distanceToRouteKm\":1.25")
                    .contains("\"maxPowerKw\":150.0");
        });
    }
}
