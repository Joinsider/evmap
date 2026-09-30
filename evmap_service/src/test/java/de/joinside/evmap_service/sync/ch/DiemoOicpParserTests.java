package de.joinside.evmap_service.sync.ch;

import de.joinside.evmap_service.sync.SourceStation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fixtures mirror the real 2026-09-30 register: one record per EVSE, loosely typed fields (a numeric
 * postcode, {@code power} as int, float, string and {@code null}, names as a list, an object or
 * {@code null}), the {@code 50.0, -15.0} placeholder, and one EVSE-ID under two operators.
 */
class DiemoOicpParserTests {
    private static final Instant FETCHED_AT = Instant.parse("2026-09-30T12:00:00Z");

    /** One EVSE. Defaults are a public 22 kW Type 2 socket on the Zürich Bahnhofstrasse. */
    private static final class Evse {
        private String id = "CH*CCI*E1";
        private String accessibility = "Free publicly accessible";
        private String street = "Bahnhofstrasse 1";
        private String city = "Zürich";
        private String postalCode = "\"8001\"";
        private String country = "CHE";
        private String coordinates = "\"47.37690 8.54170\"";
        private String plugs = "[\"Type 2 Outlet\"]";
        private String facilities = "[{\"power\": \"22.0\", \"powertype\": \"AC_3_PHASE\"}]";
        private String names = "[{\"lang\": \"en\", \"value\": \"Bahnhof (en)\"}, {\"lang\": \"de\", \"value\": \"Bahnhof\"}]";

        Evse id(String value) {
            id = value;
            return this;
        }

        Evse accessibility(String value) {
            accessibility = value;
            return this;
        }

        Evse at(double latitude, double longitude) {
            coordinates = "\"" + latitude + " " + longitude + "\"";
            return this;
        }

        Evse coordinates(String raw) {
            coordinates = raw;
            return this;
        }

        Evse plugs(String json) {
            plugs = json;
            return this;
        }

        Evse facilities(String json) {
            facilities = json;
            return this;
        }

        Evse names(String json) {
            names = json;
            return this;
        }

        Evse postalCode(String json) {
            postalCode = json;
            return this;
        }

        Evse country(String value) {
            country = value;
            return this;
        }

        String render() {
            return """
                    {"EvseID": "%s", "Accessibility": "%s", "ChargingStationId": "%s",
                     "Address": {"Street": "%s", "City": "%s", "PostalCode": %s, "Country": "%s"},
                     "GeoCoordinates": {"Google": %s}, "Plugs": %s, "ChargingFacilities": %s,
                     "ChargingStationNames": %s, "SomethingNew": {"ignored": true}}
                    """.formatted(id, accessibility, id, street, city, postalCode, country, coordinates,
                    plugs, facilities, names);
        }
    }

    private static Evse evse() {
        return new Evse();
    }

    private static String feed(String operator, Evse... evses) {
        return feedOf(op(operator, evses));
    }

    private static String feedOf(String... operatorBlocks) {
        return "{\"EVSEData\": [" + String.join(",", operatorBlocks) + "]}";
    }

    private static String op(String operator, Evse... evses) {
        StringBuilder records = new StringBuilder();
        for (Evse evse : evses) records.append(records.isEmpty() ? "" : ",").append(evse.render());
        return "{\"OperatorID\": \"CH*X\", \"OperatorName\": \"" + operator + "\", \"EVSEDataRecord\": ["
                + records + "]}";
    }

    private static List<SourceStation> parse(String json) throws IOException {
        try (var stations = DiemoOicpParser.parse(new StringReader(json), FETCHED_AT)) {
            return stations.toList();
        }
    }

    @Test
    @DisplayName("maps a public EVSE onto a station with its address, operator, EVSE-ID and connector")
    void mapsAStation() throws IOException {
        List<SourceStation> stations = parse(feed("Move", evse()));

        assertThat(stations).singleElement().satisfies(station -> {
            assertThat(station.source()).isEqualTo("DIEMO");
            assertThat(station.sourceStationId()).isEqualTo("47.37690,8.54170");
            assertThat(station.name()).isEqualTo("Bahnhof");
            assertThat(station.street()).isEqualTo("Bahnhofstrasse 1");
            assertThat(station.city()).isEqualTo("Zürich");
            assertThat(station.postalCode()).isEqualTo("8001");
            assertThat(station.countryCode()).isEqualTo("CH");
            assertThat(station.operatorName()).isEqualTo("Move");
            assertThat(station.latitude()).isEqualTo(47.3769);
            assertThat(station.longitude()).isEqualTo(8.5417);
            // Live occupancy is not master data (ADR 0015), and the register states no service state.
            assertThat(station.availabilityStatus()).isNull();
            assertThat(station.lastUpdatedAt()).isEqualTo(FETCHED_AT);
            assertThat(station.connectors()).isEmpty();
            assertThat(station.chargePoints()).singleElement().satisfies(chargePoint -> {
                assertThat(chargePoint.sourceChargePointId()).isEqualTo("CH*CCI*E1");
                assertThat(chargePoint.evseId()).isEqualTo("CH*CCI*E1");
                assertThat(chargePoint.connectors()).singleElement().satisfies(connector -> {
                    assertThat(connector.connectorType()).isEqualTo("Type 2");
                    assertThat(connector.powerKw()).isEqualByComparingTo("22");
                    assertThat(connector.quantity()).isOne();
                });
            });
        });
    }

    @Test
    @DisplayName("EVSEs sharing a position become one station, even with different station ids and operators")
    void groupsByPosition() throws IOException {
        List<SourceStation> stations = parse(feedOf(
                op("Move", evse().id("CH*A*E1"), evse().id("CH*A*E2")),
                op("swisscharge.ch AG", evse().id("CH*B*E3")),
                op("Move", evse().id("CH*A*E4").at(46.9480, 7.4474))));

        assertThat(stations).hasSize(2);
        assertThat(stations.stream().map(s -> s.chargePoints().size()).sorted().toList()).containsExactly(1, 3);
        // The most frequent operator names the site.
        assertThat(stations).filteredOn(s -> s.chargePoints().size() == 3).singleElement()
                .extracting(SourceStation::operatorName).isEqualTo("Move");
    }

    @Test
    @DisplayName("stations of this source are never closer than the ingestion's 30 m match")
    void clustersWithinTheRadius() throws IOException {
        // ~11 m and ~22 m north of the anchor join it; ~44 m does not.
        List<SourceStation> stations = parse(feed("Move",
                evse().id("CH*A*E1").at(47.37690, 8.54170),
                evse().id("CH*A*E2").at(47.37700, 8.54170),
                evse().id("CH*A*E3").at(47.37710, 8.54170),
                evse().id("CH*A*E4").at(47.37730, 8.54170)));

        assertThat(stations).hasSize(2);
        assertThat(stations).extracting(s -> s.chargePoints().size()).containsExactlyInAnyOrder(3, 1);
        assertThat(stations).extracting(SourceStation::sourceStationId).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("yields the same stations whatever the order of the records")
    void isOrderIndependent() throws IOException {
        List<Evse> evses = new ArrayList<>(List.of(
                evse().id("CH*A*E1").at(47.37690, 8.54170), evse().id("CH*A*E2").at(47.37700, 8.54170),
                evse().id("CH*A*E3").at(47.37710, 8.54170), evse().id("CH*A*E4").at(46.9480, 7.4474)));
        List<String> first = signature(parse(feed("Move", evses.toArray(Evse[]::new))));
        Collections.reverse(evses);
        List<String> second = signature(parse(feed("Move", evses.toArray(Evse[]::new))));

        assertThat(second).isEqualTo(first);
    }

    private static List<String> signature(List<SourceStation> stations) {
        return stations.stream()
                .map(s -> s.sourceStationId() + "=" + s.chargePoints().stream()
                        .map(SourceStation.SourceChargePoint::evseId).sorted().collect(Collectors.joining("+")))
                .sorted().toList();
    }

    @Test
    @DisplayName("skips restricted and test EVSEs, and a site with nothing public is not emitted")
    void skipsNonPublic() throws IOException {
        List<SourceStation> stations = parse(feedOf(
                op("Move",
                        evse().id("CH*A*E1").accessibility("Restricted access"),
                        evse().id("CH*A*E2").accessibility("Test Station"),
                        evse().id("CH*A*E3").accessibility("Paying publicly accessible").at(46.9480, 7.4474),
                        evse().id("CH*A*E4").accessibility("Restricted access").at(46.9480, 7.4474))));

        assertThat(stations).singleElement().satisfies(station -> {
            assertThat(station.chargePoints()).extracting(SourceStation.SourceChargePoint::evseId)
                    .containsExactly("CH*A*E3");
            assertThat(station.latitude()).isEqualTo(46.948);
        });
    }

    @Test
    @DisplayName("drops the placeholder coordinate, unreadable positions and stations outside the country")
    void dropsBadPositions() throws IOException {
        List<SourceStation> stations = parse(feed("Move",
                evse().id("CH*A*E1"),
                evse().id("CH*A*E2").coordinates("\"50.0 -15.0\""),
                evse().id("CH*A*E3").coordinates("\"None None\""),
                evse().id("CH*A*E4").coordinates("null"),
                evse().id("CH*A*E5").coordinates("\"48.4 10.9\"")));

        assertThat(stations).singleElement()
                .extracting(s -> s.chargePoints().size()).isEqualTo(1);
    }

    @Test
    @DisplayName("an EVSE-ID listed by two operators is kept once, and a restricted listing loses to a public one")
    void repeatedEvseIds() throws IOException {
        List<SourceStation> stations = parse(feedOf(
                op("eCarUp", evse().id("CH*ECU*E1").accessibility("Restricted access")),
                op("swisscharge.ch AG", evse().id("CH*ECU*E1"), evse().id("CH*ECU*E1"))));

        assertThat(stations).singleElement().satisfies(station -> {
            assertThat(station.chargePoints()).hasSize(1);
            assertThat(station.operatorName()).isEqualTo("swisscharge.ch AG");
        });
    }

    @Test
    @DisplayName("treats a missing or zero rating as unknown, and reads ratings of every JSON type")
    void readsPowerOfAnyType() throws IOException {
        List<SourceStation> stations = parse(feed("Move",
                evse().id("CH*A*E1").facilities("[{\"power\": 22}]"),
                evse().id("CH*A*E2").facilities("[{\"power\": 11.0}]"),
                evse().id("CH*A*E3").facilities("[{\"power\": \"50.00\"}]"),
                evse().id("CH*A*E4").facilities("[{\"power\": 0}]"),
                evse().id("CH*A*E5").facilities("[{\"power\": null}]"),
                evse().id("CH*A*E6").facilities("[{\"power\": \"n/a\"}]"),
                evse().id("CH*A*E7").facilities("[]")));

        assertThat(powers(stations)).containsExactly(
                new BigDecimal("22"), new BigDecimal("11"), new BigDecimal("50"), null, null, null, null);
        // One scale for one rating: 22, 22.0 and "22.0" must not become three connector keys.
        assertThat(powers(stations).get(0).scale()).isZero();
    }

    private static List<BigDecimal> powers(List<SourceStation> stations) {
        return stations.stream().flatMap(s -> s.chargePoints().stream())
                .sorted(java.util.Comparator.comparing(SourceStation.SourceChargePoint::evseId))
                .flatMap(cp -> cp.connectors().stream()).map(SourceStation.SourceConnector::powerKw).toList();
    }

    @Test
    @DisplayName("pairs the plugs of one EVSE with their ratings by position")
    void pairsPlugsWithRatings() throws IOException {
        List<SourceStation> stations = parse(feed("Move", evse()
                .plugs("[\"CHAdeMO\", \"CCS Combo 2 Plug (Cable Attached)\"]")
                .facilities("[{\"power\": 50, \"powertype\": \"DC\"}, {\"power\": 300, \"powertype\": \"DC\"}]")));

        assertThat(stations.get(0).chargePoints().get(0).connectors())
                .extracting(SourceStation.SourceConnector::connectorType, c -> c.powerKw().intValue())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("CHAdeMO", 50),
                        org.assertj.core.groups.Tuple.tuple("CCS", 300));
    }

    @Test
    @DisplayName("collapses an EVSE with an implausible number of identical plugs to one connector")
    void collapsesOversizedEvse() throws IOException {
        String plugs = "[" + "\"Type 2 Outlet\",".repeat(156) + "\"Type 2 Outlet\"]";
        String facilities = "[" + "{\"power\": 22},".repeat(157) + "{\"power\": 22}]";

        List<SourceStation> stations = parse(feed("swisscharge.ch AG", evse().plugs(plugs).facilities(facilities)));

        assertThat(stations.get(0).chargePoints().get(0).connectors()).singleElement().satisfies(connector -> {
            assertThat(connector.connectorType()).isEqualTo("Type 2");
            assertThat(connector.powerKw()).isEqualByComparingTo("22");
            assertThat(connector.quantity()).isOne();
        });
    }

    @Test
    @DisplayName("maps the register's plug labels onto the connector vocabulary, and keeps unknown ones as they are")
    void normalizesPlugLabels() throws IOException {
        List<SourceStation> stations = parse(feed("Move", evse().plugs(
                "[\"Type 2 Connector (Cable Attached)\", \"Tesla Connector\", \"Type 1 Connector (Cable Attached)\","
                        + " \"CCS Combo 1 Plug (Cable Attached)\", \"Type J Swiss Standard\"]").facilities("[]")));

        assertThat(stations.get(0).chargePoints().get(0).connectors())
                .extracting(SourceStation.SourceConnector::connectorType)
                .containsExactly("Type 2", "Tesla", "Type 1", "CCS", "Type J Swiss Standard");
    }

    @Test
    @DisplayName("tolerates the loose typing of the feed: numeric postcode, object or missing names, absent fields")
    void toleratesLooseTyping() throws IOException {
        List<SourceStation> stations = parse(feedOf(op("Move",
                evse().id("CH*A*E1").postalCode("8001").names("{\"lang\": \"en\", \"value\": \"Single name\"}"),
                evse().id("CH*A*E2").at(46.9480, 7.4474).postalCode("null").names("null"),
                evse().id("CH*A*E3").at(46.2000, 6.1500).postalCode("\"  \"").names("[]"))));

        assertThat(stations).hasSize(3);
        SourceStation zurich = stations.stream().filter(s -> s.latitude() > 47).findFirst().orElseThrow();
        assertThat(zurich.postalCode()).isEqualTo("8001");
        assertThat(zurich.name()).isEqualTo("Single name");
        // No usable name: the street stands in, so a station is never nameless.
        SourceStation bern = stations.stream().filter(s -> s.latitude() > 46.9 && s.latitude() < 47).findFirst().orElseThrow();
        assertThat(bern.name()).isEqualTo("Bahnhofstrasse 1");
        assertThat(bern.postalCode()).isNull();
        assertThat(stations.stream().filter(s -> s.latitude() < 46.5).findFirst().orElseThrow().postalCode()).isNull();
    }

    @Test
    @DisplayName("marks the Liechtenstein station as such, and everything else as Switzerland")
    void countryCodes() throws IOException {
        List<SourceStation> stations = parse(feed("Move",
                evse().id("CH*A*E1").country("CHE"),
                evse().id("CH*A*E2").at(47.1660, 9.5554).country("LI"),
                evse().id("CH*A*E3").at(46.9480, 7.4474).country("CH")));

        assertThat(stations).extracting(SourceStation::countryCode).containsExactlyInAnyOrder("CH", "LI", "CH");
    }

    @Test
    @DisplayName("fails with the reason when the document is not the register")
    void rejectsAChangedFormat() {
        assertThatThrownBy(() -> parse("{\"Unexpected\": []}"))
                .isInstanceOf(IOException.class).hasMessageContaining("EVSEData");
        assertThatThrownBy(() -> parse("not json")).isInstanceOf(IOException.class);
    }
}
