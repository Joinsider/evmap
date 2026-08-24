package de.joinside.evmap_service.sync.irve;

import de.joinside.evmap_service.sync.SourceStation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Fixtures mirror the real 2026-07-29 consolidation (schema 2.3.1): one row per charge point, rows of
 * a station scattered rather than adjacent, booleans in all eight spellings the file actually uses,
 * {@code condition_acces} in its mojibake variants, and ratings stated in watts.
 */
class IrveCsvParserTests {

    /** The columns this adapter reads, in publication order; the real file has 52. */
    private static final String HEADER = String.join(",",
            "nom_amenageur", "nom_operateur", "nom_enseigne", "id_station_itinerance", "id_station_local",
            "nom_station", "adresse_station", "nbre_pdc", "id_pdc_itinerance", "puissance_nominale",
            "prise_type_ef", "prise_type_2", "prise_type_combo_ccs", "prise_type_chademo", "prise_type_autre",
            "condition_acces", "date_maj", "last_modified",
            "consolidated_longitude", "consolidated_latitude", "consolidated_code_postal", "consolidated_commune");

    /** One charge point. Defaults are a plain public 22 kW Type 2 point in Haguenau. */
    private static final class Row {
        private String amenageur = "Etalab SAS";
        private String operator = "Izivia";
        private String brand = "Réseau Alsace";
        private String stationId = "FRS01P0001";
        private String localId = "local-1";
        private String stationName = "Mairie de Haguenau";
        private String address = "93 route de Bitche, 67506 Haguenau Cedex";
        private String pointId = "FRS01E0001";
        private String power = "22";
        private String ef = "false";
        private String type2 = "true";
        private String ccs = "false";
        private String chademo = "false";
        private String other = "false";
        private String access = "Accès libre";
        private String dateMaj = "2026-07-27";
        private String lastModified = "2026-07-28T21:00:49.553000+00:00";
        private String longitude = "7.762694";
        private String latitude = "48.825613";
        private String postalCode = "67500";
        private String commune = "Haguenau";

        private String render() {
            return String.join(",", quote(amenageur), quote(operator), quote(brand), stationId, localId,
                    quote(stationName), quote(address), "4", pointId, power,
                    ef, type2, ccs, chademo, other,
                    quote(access), dateMaj, lastModified, longitude, latitude, postalCode, quote(commune));
        }

        private static String quote(String value) {
            return "\"" + value + "\"";
        }
    }

    private static Row row() {
        return new Row();
    }

    private static List<SourceStation> parse(Row... rows) {
        StringBuilder csv = new StringBuilder(HEADER).append("\n");
        for (Row row : rows) csv.append(row.render()).append("\n");
        try (Stream<SourceStation> stations = parseCsv(csv.toString())) {
            return stations.toList();
        }
    }

    private static Stream<SourceStation> parseCsv(String csv) {
        try {
            return IrveCsvParser.parse(new StringReader(csv));
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    @DisplayName("maps a charge point onto a station")
    void mapsAStation() {
        List<SourceStation> stations = parse(row());

        assertThat(stations).singleElement().satisfies(station -> {
            assertThat(station.source()).isEqualTo("IRVE");
            assertThat(station.sourceStationId()).isEqualTo("FRS01P0001");
            assertThat(station.name()).isEqualTo("Mairie de Haguenau");
            assertThat(station.countryCode()).isEqualTo("FR");
            assertThat(station.operatorName()).isEqualTo("Izivia");
            assertThat(station.latitude()).isEqualTo(48.825613);
            assertThat(station.longitude()).isEqualTo(7.762694);
            assertThat(station.street()).isEqualTo("93 route de Bitche");
            assertThat(station.postalCode()).isEqualTo("67500");
            assertThat(station.city()).isEqualTo("Haguenau");
            assertThat(station.connectors())
                    .containsExactly(new SourceStation.SourceConnector("Type 2", new BigDecimal("22"), 1));
        });
    }

    @Test
    @DisplayName("the consolidated schema publishes no operational status, so availability stays unknown")
    void reportsNoAvailability() {
        assertThat(parse(row())).singleElement()
                .extracting(SourceStation::availabilityStatus).isNull();
    }

    @Test
    @DisplayName("groups charge points into one station even when their rows are far apart")
    void groupsNonAdjacentRows() {
        // 48.080 of the 64.251 stations in the real file are re-entered after other stations, which is
        // exactly what a running comparison against the previous row would get wrong.
        Row first = row();
        first.pointId = "FRS01E0001";
        Row other = row();
        other.stationId = "FRS02P0002";
        other.stationName = "Parking Gare";
        other.pointId = "FRS02E0001";
        Row backToFirst = row();
        backToFirst.pointId = "FRS01E0002";

        List<SourceStation> stations = parse(first, other, backToFirst);

        assertThat(stations).hasSize(2);
        assertThat(stations)
                .filteredOn(station -> station.sourceStationId().equals("FRS01P0001"))
                .singleElement()
                .satisfies(station -> assertThat(station.connectors())
                        // Two charge points of the same kind become one connector with a quantity.
                        .containsExactly(new SourceStation.SourceConnector("Type 2", new BigDecimal("22"), 2)));
    }

    @Test
    @DisplayName("maps each connector column onto its canonical type, and ignores 'autre'")
    void mapsConnectorColumns() {
        Row row = row();
        row.ef = "true";
        row.type2 = "true";
        row.ccs = "true";
        row.chademo = "true";
        // Says a plug exists but not which one — a connector nobody can filter for is worse than a gap.
        row.other = "true";

        assertThat(parse(row)).singleElement()
                .extracting(SourceStation::connectors).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.list(SourceStation.SourceConnector.class))
                .extracting(SourceStation.SourceConnector::connectorType)
                .containsExactlyInAnyOrder("Type 2", "CCS", "CHAdeMO", "Schuko");
    }

    @Test
    @DisplayName("accepts every boolean spelling the consolidation actually contains")
    void acceptsAllBooleanSpellings() {
        for (String truthy : List.of("true", "True", "TRUE", "1")) {
            Row row = row();
            row.type2 = "false";
            row.chademo = truthy;
            assertThat(parse(row)).singleElement()
                    .extracting(station -> station.connectors().getFirst().connectorType())
                    .as("spelling %s", truthy)
                    .isEqualTo("CHAdeMO");
        }
        for (String falsy : List.of("false", "False", "FALSE", "0", "")) {
            Row row = row();
            row.type2 = falsy;
            assertThat(parse(row)).singleElement()
                    .extracting(SourceStation::connectors).asList()
                    .as("spelling %s", falsy)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("reads ratings stated in watts as kilowatts")
    void correctsWattRatings() {
        // 769 rows of the 2026-07-29 edition state 7360 — a 32 A single-phase point, not a 7 MW one.
        Row watts = row();
        watts.power = "7360";
        assertThat(parse(watts)).singleElement()
                .extracting(station -> station.connectors().getFirst().powerKw())
                .isEqualTo(new BigDecimal("7.36"));

        Row roundWatts = row();
        roundWatts.power = "22000";
        assertThat(parse(roundWatts)).singleElement()
                .extracting(station -> station.connectors().getFirst().powerKw())
                .isEqualTo(new BigDecimal("22"));

        Row kilowatts = row();
        kilowatts.power = "3.7";
        assertThat(parse(kilowatts)).singleElement()
                .extracting(station -> station.connectors().getFirst().powerKw())
                .isEqualTo(new BigDecimal("3.7"));
    }

    @Test
    @DisplayName("treats a zero rating as absent rather than as a charge point delivering nothing")
    void dropsNonPositiveRatings() {
        Row row = row();
        row.power = "0";
        assertThat(parse(row)).singleElement()
                .extracting(station -> station.connectors().getFirst().powerKw()).isNull();
    }

    @Test
    @DisplayName("skips stations whose every charge point is restricted")
    void skipsRestrictedStations() {
        Row restricted = row();
        restricted.access = "Accès réservé";

        assertThat(parse(restricted)).isEmpty();
    }

    @Test
    @DisplayName("keeps a station that mixes restricted and public charge points")
    void keepsPartiallyPublicStations() {
        // 726 stations in the real file state both. Dropping them would remove chargers a driver can use.
        Row restricted = row();
        restricted.access = "Accès réservé";
        restricted.pointId = "FRS01E0001";
        Row open = row();
        open.access = "Accès libre";
        open.pointId = "FRS01E0002";

        assertThat(parse(restricted, open)).singleElement()
                .extracting(SourceStation::sourceStationId).isEqualTo("FRS01P0001");
    }

    @Test
    @DisplayName("recognises the restriction through the file's mojibake encodings")
    void survivesMojibakeAccess() {
        // All five spellings below occur in the 2026-07-29 edition: publishers writing Latin-1 into a
        // UTF-8 file. An equality check against "Accès libre" would misread four of them.
        for (String libre : List.of("Accès libre", "Accčs libre", "AccĂ¨s libre", "Acc¸s libre", "Accs libre")) {
            Row row = row();
            row.access = libre;
            assertThat(parse(row)).as("public spelling %s", libre).hasSize(1);
        }
        Row reserved = row();
        reserved.access = "AccĂ¨s rĂ©servĂ©";
        assertThat(parse(reserved)).as("mojibake réservé").isEmpty();
    }

    @Test
    @DisplayName("an unrecognised access condition keeps the station rather than dropping it")
    void unknownAccessKeepsTheStation() {
        Row row = row();
        row.access = "Sur rendez-vous";
        assertThat(parse(row)).hasSize(1);
    }

    @Test
    @DisplayName("recovers postal code and commune from the address line when the consolidated columns are blank")
    void recoversAddressParts() {
        // Filled for only 58 % and 65 % of the real rows; the address line yields the code for 97 %.
        Row row = row();
        row.postalCode = "";
        row.commune = "";

        assertThat(parse(row)).singleElement().satisfies(station -> {
            assertThat(station.street()).isEqualTo("93 route de Bitche");
            assertThat(station.postalCode()).isEqualTo("67506");
            assertThat(station.city()).isEqualTo("Haguenau Cedex");
        });
    }

    @Test
    @DisplayName("keeps the whole address line as the street when it carries no postal code")
    void keepsUnstructuredAddresses() {
        Row row = row();
        row.address = "Parking du marché";
        row.postalCode = "";
        row.commune = "";

        assertThat(parse(row)).singleElement().satisfies(station -> {
            assertThat(station.street()).isEqualTo("Parking du marché");
            assertThat(station.postalCode()).isEmpty();
            assertThat(station.city()).isEmpty();
        });
    }

    @Test
    @DisplayName("prefers the operator-declared update date over the consolidation's own timestamp")
    void prefersDateMaj() {
        assertThat(parse(row())).singleElement()
                .extracting(SourceStation::lastUpdatedAt)
                .isEqualTo(Instant.parse("2026-07-27T00:00:00Z"));
    }

    @Test
    @DisplayName("falls back to last_modified when date_maj is missing or malformed")
    void fallsBackToLastModified() {
        Row row = row();
        row.dateMaj = "";
        assertThat(parse(row)).singleElement()
                .extracting(SourceStation::lastUpdatedAt)
                .isEqualTo(Instant.parse("2026-07-28T21:00:49.553Z"));
    }

    @Test
    @DisplayName("reads the unpadded date at least one row per edition carries")
    void readsUnpaddedDates() {
        Row row = row();
        row.dateMaj = "2026-7-9";
        assertThat(parse(row)).singleElement()
                .extracting(SourceStation::lastUpdatedAt)
                .isEqualTo(Instant.parse("2026-07-09T00:00:00Z"));
    }

    @Test
    @DisplayName("takes the newest update date across a station's charge points")
    void takesTheNewestUpdate() {
        Row older = row();
        older.dateMaj = "2026-01-01";
        older.pointId = "FRS01E0001";
        Row newer = row();
        newer.dateMaj = "2026-06-15";
        newer.pointId = "FRS01E0002";

        assertThat(parse(older, newer)).singleElement()
                .extracting(SourceStation::lastUpdatedAt)
                .isEqualTo(Instant.parse("2026-06-15T00:00:00Z"));
    }

    @Test
    @DisplayName("drops rows without usable coordinates, including the null island")
    void dropsUnusableCoordinates() {
        Row nullIsland = row();
        nullIsland.latitude = "0";
        nullIsland.longitude = "0";
        assertThat(parse(nullIsland)).isEmpty();

        Row blank = row();
        blank.latitude = "";
        blank.longitude = "";
        assertThat(parse(blank)).isEmpty();

        Row outOfRange = row();
        outOfRange.latitude = "148.8";
        assertThat(parse(outOfRange)).isEmpty();
    }

    @Test
    @DisplayName("falls back to the local id when the roaming id is blank")
    void fallsBackToTheLocalId() {
        Row row = row();
        row.stationId = "";
        assertThat(parse(row)).singleElement()
                .extracting(SourceStation::sourceStationId).isEqualTo("local-1");
    }

    @Test
    @DisplayName("never groups on prose written where an identifier belongs")
    void rejectsPlaceholderIds() {
        // 1.192 rows of the real edition say "Non concerné" here, from unrelated operators all over
        // France. Grouping on it merged 369 separate locations into one station whose coordinates came
        // from whichever row happened to be read first.
        Row lyon = row();
        lyon.stationId = "Non concerné";
        lyon.localId = "";
        lyon.latitude = "45.764043";
        lyon.longitude = "4.835659";
        Row lille = row();
        lille.stationId = "Non concerné";
        lille.localId = "";
        lille.latitude = "50.62925";
        lille.longitude = "3.057256";

        assertThat(parse(lyon, lille)).isEmpty();
    }

    @Test
    @DisplayName("falls through a placeholder roaming id to a usable local id")
    void fallsThroughAPlaceholderToTheLocalId() {
        Row row = row();
        row.stationId = "Non concerné";
        assertThat(parse(row)).singleElement()
                .extracting(SourceStation::sourceStationId).isEqualTo("local-1");
    }

    @Test
    @DisplayName("rejects the local id's placeholders too, which are spelled several ways")
    void rejectsPlaceholderLocalIds() {
        for (String placeholder : List.of("Non renseigné", "Non concerné", "non concerné", "NON CONCERNE")) {
            Row row = row();
            row.stationId = "";
            row.localId = placeholder;
            assertThat(parse(row)).as("placeholder %s", placeholder).isEmpty();
        }
    }

    @Test
    @DisplayName("counts one rating as one connector however the publisher spelled the number")
    void mergesRatingsThatDifferOnlyInScale() {
        // BigDecimal.equals compares the scale as well as the value, and all three spellings occur in
        // the real file — one station came out with 22 ×213, 22.00 ×6 and 22.0 ×1 side by side.
        Row plain = row();
        plain.power = "22";
        plain.pointId = "FRS01E0001";
        Row oneZero = row();
        oneZero.power = "22.0";
        oneZero.pointId = "FRS01E0002";
        Row twoZeros = row();
        twoZeros.power = "22.00";
        twoZeros.pointId = "FRS01E0003";

        assertThat(parse(plain, oneZero, twoZeros)).singleElement()
                .extracting(SourceStation::connectors).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.list(SourceStation.SourceConnector.class))
                .singleElement()
                .satisfies(connector -> {
                    assertThat(connector.quantity()).isEqualTo(3);
                    assertThat(connector.powerKw()).isEqualByComparingTo("22");
                });
    }

    @Test
    @DisplayName("falls back through brand and operator when the station has no name")
    void fallsBackForTheName() {
        Row noName = row();
        noName.stationName = "";
        assertThat(parse(noName)).singleElement()
                .extracting(SourceStation::name).isEqualTo("Réseau Alsace");

        Row nameless = row();
        nameless.stationName = "";
        nameless.brand = "";
        assertThat(parse(nameless)).singleElement()
                .extracting(SourceStation::name).isEqualTo("Izivia");
    }

    @Test
    @DisplayName("falls back to the aménageur when no operator is named")
    void fallsBackToTheAmenageur() {
        Row row = row();
        row.operator = "";
        assertThat(parse(row)).singleElement()
                .extracting(SourceStation::operatorName).isEqualTo("Etalab SAS");
    }

    @Test
    @DisplayName("merges different plugs and powers of one station into distinct connectors")
    void mergesDistinctPlugs() {
        Row slow = row();
        slow.power = "22";
        slow.pointId = "FRS01E0001";
        Row fast = row();
        fast.power = "150";
        fast.type2 = "false";
        fast.ccs = "true";
        fast.pointId = "FRS01E0002";
        Row anotherFast = row();
        anotherFast.power = "150";
        anotherFast.type2 = "false";
        anotherFast.ccs = "true";
        anotherFast.pointId = "FRS01E0003";

        assertThat(parse(slow, fast, anotherFast)).singleElement()
                .extracting(SourceStation::connectors).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.list(SourceStation.SourceConnector.class))
                .extracting(SourceStation.SourceConnector::connectorType,
                        SourceStation.SourceConnector::powerKw,
                        SourceStation.SourceConnector::quantity)
                .containsExactlyInAnyOrder(
                        tuple("Type 2", new BigDecimal("22"), 1),
                        tuple("CCS", new BigDecimal("150"), 2));
    }

    @Test
    @DisplayName("says so when the schema no longer carries the station id column")
    void failsWithAnActionableMessage() {
        assertThatThrownBy(() -> IrveCsvParser.parse(new StringReader("nom_station,adresse_station\nFoo,Bar\n")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("id_station_itinerance");
    }

    @Test
    @DisplayName("says so when the file is empty")
    void rejectsAnEmptyFile() {
        assertThatThrownBy(() -> IrveCsvParser.parse(new StringReader("")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("empty");
    }
}
