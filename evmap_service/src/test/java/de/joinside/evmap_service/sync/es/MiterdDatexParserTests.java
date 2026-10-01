package de.joinside.evmap_service.sync.es;

import de.joinside.evmap_service.sync.SourceStation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * Fixtures mirror the real 2026-10-01 register: one site per operator, the EVSE-ID in the charge point's
 * {@code fac:name}, address lines prefixed with their label, a postcode that lost its leading zero,
 * ratings in watts, and several sites of different operators on one parking lot.
 */
class MiterdDatexParserTests {
    private static final Instant FETCHED_AT = Instant.parse("2026-10-01T12:00:00Z");

    /** One charge point. Defaults are a 100 kW CCS plug with a well-formed EVSE-ID. */
    private static final class Point {
        private final String id;
        private String evseName;
        private final List<String[]> connectors = new ArrayList<>();

        Point(String id) {
            this.id = id;
            this.evseName = "ES*IBD*E" + id;
            connectors.add(new String[]{"iec62196T2COMBO", "100000.0"});
        }

        Point evse(String value) {
            evseName = value;
            return this;
        }

        Point plugs(String... typesAndWatts) {
            connectors.clear();
            for (int i = 0; i < typesAndWatts.length; i += 2)
                connectors.add(new String[]{typesAndWatts[i], typesAndWatts[i + 1]});
            return this;
        }
    }

    /** One site, defaults are an Iberdrola site in Palma with one charge point. */
    private static final class Site {
        private String id = "2024000001";
        private String name = "Parking Es Mercat";
        private String updated = "2026-09-30T14:09:20.000+02:00";
        private String latitude = "39.5948";
        private String longitude = "2.63586";
        private String postcode = "<locx:postcode>7011</locx:postcode>";
        private String addressLines = line(1, "Dirección: Camí dels Reis 166") + line(2, "Municipio: Palma")
                + line(3, "Provincia: Balears, Illes");
        private String operator = "IBERDROLA CLIENTES S.A.U";
        private final List<Point> points = new ArrayList<>(List.of(new Point("A1")));

        Site id(String value) {
            id = value;
            return this;
        }

        Site name(String value) {
            name = value;
            return this;
        }

        Site updated(String value) {
            updated = value;
            return this;
        }

        Site at(double latitude, double longitude) {
            this.latitude = Double.toString(latitude);
            this.longitude = Double.toString(longitude);
            return this;
        }

        Site postcode(String value) {
            postcode = "<locx:postcode>" + value + "</locx:postcode>";
            return this;
        }

        Site address(String... lines) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < lines.length; i++) builder.append(line(i + 1, lines[i]));
            addressLines = builder.toString();
            return this;
        }

        Site operator(String value) {
            operator = value;
            return this;
        }

        Site points(Point... values) {
            points.clear();
            points.addAll(List.of(values));
            return this;
        }
    }

    private static String line(int order, String text) {
        return "<locx:addressLine order=\"" + order + "\"><locx:type>generalTextLine</locx:type><locx:text><com:values>"
                + "<com:value lang=\"es\">" + text + "</com:value></com:values></locx:text></locx:addressLine>";
    }

    private static String xml(Site... sites) {
        StringBuilder out = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <d2:payload xmlns:d2="http://datex2.eu/schema/3/d2Payload" \
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="egi:EnergyInfrastructureTablePublication" \
                xmlns:com="http://datex2.eu/schema/3/common" xmlns:loc="http://datex2.eu/schema/3/locationReferencing" \
                xmlns:egi="http://datex2.eu/schema/3/energyInfrastructure" xmlns:fac="http://datex2.eu/schema/3/facilities" \
                xmlns:locx="http://datex2.eu/schema/3/locationExtension">
                <egi:energyInfrastructureTable id="ELECTROLINERAS" version="20261001">
                """);
        for (Site site : sites) out.append(siteXml(site));
        return out.append("</egi:energyInfrastructureTable></d2:payload>").toString();
    }

    private static String siteXml(Site site) {
        StringBuilder out = new StringBuilder();
        out.append("<egi:energyInfrastructureSite").append(site.id == null ? "" : " id=\"" + site.id + "\"").append(" version=\"\">")
                .append("<fac:name><com:values><com:value lang=\"es\">").append(site.name).append("</com:value></com:values></fac:name>")
                .append("<fac:lastUpdated>").append(site.updated).append("</fac:lastUpdated>")
                .append("<fac:accessibility xsi:nil=\"true\"/>")
                .append("<fac:locationReference xsi:type=\"loc:PointLocation\"><loc:_locationReferenceExtension>")
                .append("<loc:facilityLocation><locx:address>").append(site.postcode).append(site.addressLines)
                .append("</locx:address></loc:facilityLocation></loc:_locationReferenceExtension>")
                .append("<loc:coordinatesForDisplay><loc:latitude>").append(site.latitude).append("</loc:latitude>")
                .append("<loc:longitude>").append(site.longitude).append("</loc:longitude></loc:coordinatesForDisplay>")
                .append("</fac:locationReference>")
                .append("<fac:operator xsi:type=\"fac:OrganisationSpecification\" id=\"ES*915\">")
                .append("<fac:name><com:values><com:value lang=\"es\">").append(site.operator)
                .append("</com:value></com:values></fac:name></fac:operator>")
                .append("<fac:supplementalFacility xsi:type=\"fac:SupplementalServiceFacility\" id=\"0\">")
                .append("<fac:serviceFacilityType>restaurant</fac:serviceFacilityType></fac:supplementalFacility>")
                .append("<egi:energyInfrastructureStation id=\"").append(site.id).append("_1\" version=\"\">")
                .append("<egi:authenticationAndIdentificationMethods>rfid</egi:authenticationAndIdentificationMethods>");
        for (Point point : site.points) {
            out.append("<egi:refillPoint xsi:type=\"egi:ElectricChargingPoint\"")
                    .append(point.id == null ? "" : " id=\"" + point.id + "\"").append(" version=\"\">")
                    .append("<fac:name><com:values><com:value lang=\"es\">").append(point.evseName)
                    .append("</com:value></com:values></fac:name>");
            for (String[] connector : point.connectors) {
                out.append("<egi:connector><egi:connectorType>").append(connector[0]).append("</egi:connectorType>")
                        .append("<egi:chargingMode>mode4DC</egi:chargingMode>")
                        .append("<egi:maxPowerAtSocket>").append(connector[1]).append("</egi:maxPowerAtSocket>")
                        .append("<egi:voltage>920.0</egi:voltage></egi:connector>");
            }
            out.append("</egi:refillPoint>");
        }
        return out.append("</egi:energyInfrastructureStation></egi:energyInfrastructureSite>").toString();
    }

    private static List<SourceStation> parse(String document) throws IOException {
        try (var stations = MiterdDatexParser.parse(new StringReader(document), FETCHED_AT)) {
            return stations.collect(Collectors.toList());
        }
    }

    private static List<SourceStation> parse(Site... sites) throws IOException {
        return parse(xml(sites));
    }

    // ── mapping ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("maps a site onto a station with its address read by label, its postcode padded and no status")
    void mapsASite() throws IOException {
        List<SourceStation> stations = parse(new Site());

        assertThat(stations).singleElement().satisfies(station -> {
            assertThat(station.source()).isEqualTo("MITERD");
            assertThat(station.sourceStationId()).isEqualTo("2024000001");
            assertThat(station.name()).isEqualTo("Parking Es Mercat");
            assertThat(station.street()).isEqualTo("Camí dels Reis 166");
            assertThat(station.city()).isEqualTo("Palma");
            assertThat(station.postalCode()).isEqualTo("07011");
            assertThat(station.countryCode()).isEqualTo("ES");
            assertThat(station.operatorName()).isEqualTo("IBERDROLA CLIENTES S.A.U");
            assertThat(station.latitude()).isEqualTo(39.5948);
            assertThat(station.longitude()).isEqualTo(2.63586);
            // The register publishes no operational status, so none is invented.
            assertThat(station.availabilityStatus()).isNull();
            assertThat(station.lastUpdatedAt()).isEqualTo(Instant.parse("2026-09-30T12:09:20Z"));
            assertThat(station.connectors()).isEmpty();
            assertThat(station.chargePoints()).singleElement().satisfies(chargePoint -> {
                assertThat(chargePoint.sourceChargePointId()).isEqualTo("A1");
                assertThat(chargePoint.evseId()).isEqualTo("ES*IBD*EA1");
            });
        });
    }

    @Test
    @DisplayName("leaves a five-digit postcode alone and pads only the four-digit ones")
    void padsFourDigitPostcodes() throws IOException {
        assertThat(parse(new Site().postcode("28001"))).extracting(SourceStation::postalCode).containsExactly("28001");
        assertThat(parse(new Site().postcode("8001"))).extracting(SourceStation::postalCode).containsExactly("08001");
    }

    @Test
    @DisplayName("reads the address lines by label without accents, and takes the street for a name the site lacks")
    void readsAddressByLabel() throws IOException {
        Site site = new Site().name("   ").address("Provincia: Madrid", "MUNICIPIO: Getafe", "Direccion: Calle Mayor 3");

        assertThat(parse(site)).singleElement().satisfies(station -> {
            assertThat(station.street()).isEqualTo("Calle Mayor 3");
            assertThat(station.city()).isEqualTo("Getafe");
            assertThat(station.name()).isEqualTo("Calle Mayor 3");
        });
    }

    @Test
    @DisplayName("maps the DATEX connector enumerations onto the closed vocabulary")
    void mapsConnectorTypes() throws IOException {
        Point point = new Point("P").plugs(
                "iec62196T2", "22000.0", "iec62196T2COMBO", "50000.0", "iec62196T1COMBO", "50000.0",
                "chademo", "50000.0", "domesticF", "3680.0", "iec60309x2three32", "22000.0",
                "iec62196T1", "7400.0", "domesticE", "3680.0", "iec62196T3A", "3680.0");

        assertThat(parse(new Site().points(point)).get(0).chargePoints().get(0).connectors())
                .extracting(SourceStation.SourceConnector::connectorType)
                .containsExactly("Type 2", "CCS", "CHAdeMO", "Schuko", "CEE", "Type 1", "Type E", "Type 3A");
    }

    @Test
    @DisplayName("converts watts to kilowatts, merges equal plugs, and reads a rating below 1 kW as unknown")
    void convertsPower() throws IOException {
        Point point = new Point("P").plugs(
                "iec62196T2", "22000.0", "iec62196T2", "22000", "iec62196T2", "7400.0",
                "iec62196T2COMBO", "350000.0", "iec62196T2COMBO", "1000000.0", "chademo", "60.0");

        assertThat(parse(new Site().points(point)).get(0).chargePoints().get(0).connectors())
                .extracting(SourceStation.SourceConnector::connectorType, SourceStation.SourceConnector::powerKw,
                        SourceStation.SourceConnector::quantity)
                .containsExactly(
                        tuple("Type 2", new BigDecimal("22"), 2),
                        tuple("Type 2", new BigDecimal("7.4"), 1),
                        tuple("CCS", new BigDecimal("350"), 1),
                        tuple("CCS", new BigDecimal("1000"), 1),
                        tuple("CHAdeMO", null, 1));
    }

    @Test
    @DisplayName("keeps an EVSE-ID only where the name has its shape; the other charge points keep the plug")
    void keepsOnlyRealEvseIds() throws IOException {
        Site site = new Site().points(
                new Point("A").evse("ES*814*E-03"),
                new Point("B").evse("ES*PAV*E_VIN_005"),
                new Point("C").evse("ES*INC*E JAUME I - RRCC"),
                new Point("D").evse("ES*CAS*P3"),
                new Point("E").evse("ES*915*EES00025600000020000001"));

        assertThat(parse(site).get(0).chargePoints())
                .extracting(SourceStation.SourceChargePoint::sourceChargePointId, SourceStation.SourceChargePoint::evseId)
                .containsExactly(
                        tuple("A", "ES*814*E-03"),
                        tuple("B", "ES*PAV*E_VIN_005"),
                        tuple("C", null),
                        tuple("D", null),
                        tuple("E", "ES*915*EES00025600000020000001"));
    }

    // ── bundling ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("bundles sites of different operators on one parking lot into one station, none of them lost")
    void bundlesAcrossOperators() throws IOException {
        Site first = new Site().id("1").at(40.41600, -3.70300).operator("Endesa").points(new Point("E1"), new Point("E2"));
        Site second = new Site().id("2").at(40.41605, -3.70300).operator("Iberdrola").points(new Point("I1"));
        Site third = new Site().id("3").at(40.41610, -3.70302).operator("Endesa").points(new Point("E3"));

        List<SourceStation> stations = parse(second, third, first);

        assertThat(stations).singleElement().satisfies(station -> {
            // The southernmost site is the anchor, whatever order the register lists them in.
            assertThat(station.sourceStationId()).isEqualTo("1");
            assertThat(station.latitude()).isEqualTo(40.416);
            assertThat(station.operatorName()).isEqualTo("Endesa");
            assertThat(station.chargePoints()).extracting(SourceStation.SourceChargePoint::sourceChargePointId)
                    .containsExactly("E1", "E2", "I1", "E3");
        });
    }

    @Test
    @DisplayName("keeps sites further apart than the bundling radius as separate stations, none within 30 m of another")
    void separatesDistantSites() throws IOException {
        // 0.0004° of latitude is about 44 m.
        List<SourceStation> stations = parse(
                new Site().id("1").at(40.4160, -3.7030).points(new Point("A")),
                new Site().id("2").at(40.4164, -3.7030).points(new Point("B")),
                new Site().id("3").at(40.4168, -3.7030).points(new Point("C")),
                new Site().id("4").at(41.0, -3.7030).points(new Point("D")));

        assertThat(stations).extracting(SourceStation::sourceStationId).containsExactly("1", "2", "3", "4");
        for (SourceStation a : stations) {
            for (SourceStation b : stations) {
                if (a == b) continue;
                double metres = Math.hypot((a.latitude() - b.latitude()) * 111_320,
                        (a.longitude() - b.longitude()) * 111_320 * Math.cos(Math.toRadians(a.latitude())));
                assertThat(metres).isGreaterThan(30);
            }
        }
    }

    @Test
    @DisplayName("names a bundled station after the operator with most charge points, the first on a tie")
    void picksTheMajorityOperator() throws IOException {
        Site anchor = new Site().id("1").at(40.41600, -3.70300).operator("Endesa").points(new Point("E1"));
        Site many = new Site().id("2").at(40.41603, -3.70300).operator("Repsol").points(new Point("R1"), new Point("R2"));
        Site tie = new Site().id("3").at(41.0, -3.7).operator("Iberdrola").points(new Point("I1"));
        Site tieOther = new Site().id("4").at(41.00003, -3.7).operator("Zeta").points(new Point("Z1"));

        assertThat(parse(anchor, many, tie, tieOther)).extracting(SourceStation::operatorName)
                .containsExactly("Repsol", "Iberdrola");
    }

    @Test
    @DisplayName("stamps a bundled station with its newest site's time, and the download time where none is readable")
    void takesTheNewestUpdate() throws IOException {
        Site older = new Site().id("1").at(40.41600, -3.70300).updated("2024-01-01T10:00:00.000+01:00")
                .points(new Point("A"));
        Site newer = new Site().id("2").at(40.41603, -3.70300).updated("2026-05-01T10:00:00.000+02:00")
                .points(new Point("B"));
        Site unreadable = new Site().id("3").at(41.0, -3.7).updated("yesterday").points(new Point("C"));

        assertThat(parse(older, newer, unreadable)).extracting(SourceStation::lastUpdatedAt)
                .containsExactly(Instant.parse("2026-05-01T08:00:00Z"), FETCHED_AT);
    }

    // ── what is skipped ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("skips sites without an id, outside Spain, or without charge points")
    void skipsUnusableSites() throws IOException {
        List<SourceStation> stations = parse(
                new Site().id("1"),
                new Site().id(null).at(40.0, -3.0),
                new Site().id("3").at(0.0, 0.0),
                new Site().id("4").at(50.0, 10.0),
                new Site().id("5").at(38.0, -4.0).points());

        assertThat(stations).extracting(SourceStation::sourceStationId).containsExactly("1");
    }

    @Test
    @DisplayName("keeps a charge point id on one station only, and an EVSE-ID on one charge point only")
    void neverEmitsAnIdTwice() throws IOException {
        Site first = new Site().id("1").at(38.0, -4.0)
                .points(new Point("SAME").evse("ES*ABC*E1"), new Point("OTHER").evse("ES*ABC*E2"));
        Site second = new Site().id("2").at(39.0, -4.0)
                .points(new Point("SAME").evse("ES*ABC*E3"), new Point("THIRD").evse("ES*ABC*E-2"));

        List<SourceStation> stations = parse(first, second);

        assertThat(stations.stream().flatMap(s -> s.chargePoints().stream())
                .map(SourceStation.SourceChargePoint::sourceChargePointId)).containsExactly("SAME", "OTHER", "THIRD");
        // "ES*ABC*E-2" normalizes to the same comparison key as "ES*ABC*E2".
        assertThat(stations.get(1).chargePoints().get(0).evseId()).isNull();
    }

    // ── the document ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("fails on a document that is not the register instead of returning an empty one")
    void failsOnAWrongDocument() {
        assertThatThrownBy(() -> parse("<html><body>moved</body></html>"))
                .isInstanceOf(IOException.class).hasMessageContaining("energyInfrastructureSite");
        assertThatThrownBy(() -> parse(xml())).isInstanceOf(IOException.class)
                .hasMessageContaining("energyInfrastructureSite");
    }

    @Test
    @DisplayName("fails on malformed XML, naming it as such")
    void failsOnMalformedXml() {
        assertThatThrownBy(() -> parse("<d2:payload><unclosed>"))
                .isInstanceOf(IOException.class).hasMessageContaining("XML");
    }

    @Test
    @DisplayName("does not resolve an external entity, so a hostile document cannot read local files")
    void refusesExternalEntities() {
        String hostile = xml(new Site()).replace("<d2:payload",
                "<!DOCTYPE d2:payload [<!ENTITY secret SYSTEM \"file:///etc/hostname\">]><d2:payload")
                .replace("Parking Es Mercat", "&secret;");

        assertThatThrownBy(() -> parse(hostile)).isInstanceOf(IOException.class);
    }
}
