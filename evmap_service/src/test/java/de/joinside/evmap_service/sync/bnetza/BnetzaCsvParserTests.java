package de.joinside.evmap_service.sync.bnetza;

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
 * Fixtures mirror the real 2026-07-07 register: BOM, ten preamble lines, CRLF, {@code ;} separators,
 * German decimal comma, quoted fields containing both delimiters and newlines.
 */
class BnetzaCsvParserTests {
    private static final Instant FALLBACK = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant EDITION = Instant.parse("2026-07-07T00:00:00Z");

    private static final String PREAMBLE = """
            ﻿Ladesäulenregister Bundesnetzagentur;;;
            ;;;
            Hinweis: ;;;
            Die Liste beinhaltet die Ladeeinrichtungen aller Betreiberinnen und Betreiber, die das Anzeigeverfahren ;;;
            zum Zeitpunkt der Aktualisierung vollständig abgeschlossen haben. ;;;
            Die Zahl der öffentlich zugänglichen Ladeeinrichtungen in Deutschland ist daher größer. ;;;
            ;;;
            Letzte Aktualisierung vom: 07.07.2026;;;
            ;;;
            Allgemeine Informationen;;;1. Ladepunkt;;;;
            """;

    private static final String HEADER =
            "Ladeeinrichtungs-ID;Betreiber;Anzeigename (Karte);Status;Art der Ladeeinrichtung;Anzahl Ladepunkte;"
                    + "Nennleistung Ladeeinrichtung [kW];Inbetriebnahmedatum;Straße;Hausnummer;Adresszusatz;"
                    + "Postleitzahl;Ort;Kreis/kreisfreie Stadt;Bundesland;Breitengrad;Längengrad;"
                    + "Standortbezeichnung;Informationen zum Parkraum;Bezahlsysteme;Öffnungszeiten;"
                    + "Öffnungszeiten: Wochentage;Öffnungszeiten: Tageszeiten;"
                    + "Steckertypen1;Nennleistung Stecker1;EVSE-ID1;Public Key1;"
                    + "Steckertypen2;Nennleistung Stecker2;EVSE-ID2;Public Key2;"
                    + "Steckertypen3;Nennleistung Stecker3;EVSE-ID3;Public Key3;"
                    + "Steckertypen4;Nennleistung Stecker4;EVSE-ID4;Public Key4;"
                    + "Steckertypen5;Nennleistung Stecker5;EVSE-ID5;Public Key5;"
                    + "Steckertypen6;Nennleistung Stecker6;EVSE-ID6;Public Key6";

    /** Twenty-three general columns, then six four-column charge point groups. */
    private static String row(String id, String operator, String displayName, String status,
                              String street, String houseNumber, String postcode, String town,
                              String latitude, String longitude, String siteLabel, String... chargePoints) {
        StringBuilder row = new StringBuilder(String.join(";",
                id, operator, displayName, status, "Normalladeeinrichtung", "2", "22", "11.01.2020",
                street, houseNumber, "", postcode, town, "Landkreis Alb-Donau-Kreis", "Baden-Württemberg",
                latitude, longitude, siteLabel, "Keine Beschränkung",
                "\"RFID-Karte;Onlinezahlungsverfahren\"", "247", "\"Montag; Dienstag\"", "\"00:00-23:59; 00:00-23:59\""));
        for (int point = 0; point < 6; point++) {
            String types = point * 2 < chargePoints.length ? chargePoints[point * 2] : "";
            String powers = point * 2 + 1 < chargePoints.length ? chargePoints[point * 2 + 1] : "";
            row.append(';').append(types).append(';').append(powers).append(";;");
        }
        return row.toString();
    }

    /** As {@link #row}, with {@code groups} read as (types, powers, EVSE-ID) triples. */
    private static String rowWithEvseIds(String id, String... groups) {
        StringBuilder row = new StringBuilder(String.join(";",
                id, "Betreiber", "Mit EVSE-ID", "In Betrieb", "Normalladeeinrichtung", "2", "22", "11.01.2020",
                "Weg", "2", "", "10115", "Berlin", "Landkreis Alb-Donau-Kreis", "Baden-Württemberg",
                "52,5", "13,4", "", "Keine Beschränkung",
                "\"RFID-Karte;Onlinezahlungsverfahren\"", "247", "\"Montag; Dienstag\"", "\"00:00-23:59; 00:00-23:59\""));
        for (int point = 0; point < 6; point++) {
            int base = point * 3;
            row.append(';').append(base < groups.length ? groups[base] : "")
                    .append(';').append(base + 1 < groups.length ? groups[base + 1] : "")
                    .append(';').append(base + 2 < groups.length ? groups[base + 2] : "")
                    .append(';');
        }
        return row.toString();
    }

    private static SourceStation.SourceChargePoint onlyChargePoint(List<SourceStation> stations) {
        assertThat(stations).singleElement().extracting(SourceStation::chargePoints,
                org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(1);
        return stations.getFirst().chargePoints().getFirst();
    }

    private static String file(String... rows) {
        return PREAMBLE.replace("\n", "\r\n") + HEADER + "\r\n" + String.join("\r\n", rows) + "\r\n";
    }

    private static List<SourceStation> parse(String csv) throws IOException {
        try (Stream<SourceStation> stations = BnetzaCsvParser.parse(new StringReader(csv), FALLBACK)) {
            return stations.toList();
        }
    }

    @Test
    @DisplayName("maps a register row onto a SourceStation")
    void mapsARow() throws IOException {
        List<SourceStation> stations = parse(file(row("1010338", "Albwerk GmbH", "Albwerk Heroldstatt", "In Betrieb",
                "Am Berg", "1", "72535", "Heroldstatt", "48,442398", "9,659075", "",
                "AC Typ 2 Steckdose", "22")));

        assertThat(stations).singleElement().satisfies(station -> {
            assertThat(station.source()).isEqualTo("BNetzA");
            assertThat(station.sourceStationId()).isEqualTo("1010338");
            assertThat(station.name()).isEqualTo("Albwerk Heroldstatt");
            assertThat(station.operatorName()).isEqualTo("Albwerk GmbH");
            assertThat(station.street()).isEqualTo("Am Berg 1");
            assertThat(station.city()).isEqualTo("Heroldstatt");
            assertThat(station.postalCode()).isEqualTo("72535");
            assertThat(station.countryCode()).isEqualTo("DE");
            // German decimal comma, not a thousands separator.
            assertThat(station.latitude()).isEqualTo(48.442398);
            assertThat(station.longitude()).isEqualTo(9.659075);
        });
    }

    @Test
    @DisplayName("dates the rows from the preamble, because the register has no per-row timestamp")
    void usesTheEditionDate() throws IOException {
        List<SourceStation> stations = parse(file(row("1", "Betreiber", "", "In Betrieb",
                "Weg", "2", "10115", "Berlin", "52,5", "13,4", "", "AC Typ 2 Steckdose", "22")));

        assertThat(stations).singleElement()
                .extracting(SourceStation::lastUpdatedAt).isEqualTo(EDITION);
    }

    @Test
    @DisplayName("falls back to the supplied edition when the preamble date is unreadable")
    void fallsBackWhenTheEditionDateIsUnreadable() throws IOException {
        String csv = file(row("1", "Betreiber", "", "In Betrieb",
                "Weg", "2", "10115", "Berlin", "52,5", "13,4", "", "AC Typ 2 Steckdose", "22"))
                .replace("Letzte Aktualisierung vom: 07.07.2026", "Letzte Aktualisierung vom: demnächst");

        assertThat(parse(csv)).singleElement().extracting(SourceStation::lastUpdatedAt).isEqualTo(FALLBACK);
    }

    @Test
    @DisplayName("keeps the register's charge points apart instead of collapsing them into totals")
    void keepsChargePointsApart() throws IOException {
        // Four Ladepunkte, all Type 2 at 22 kW. These used to become one connector of quantity four,
        // which threw away both the count and each point's EVSE-ID; the station's totals are rebuilt
        // when the API serves it. See ADR 0015.
        List<SourceStation> stations = parse(file(row("2", "Betreiber", "Vier Punkte", "In Betrieb",
                "Weg", "2", "10115", "Berlin", "52,5", "13,4", "",
                "AC Typ 2 Steckdose", "22",
                "AC Typ 2 Steckdose", "22",
                "AC Typ 2 Fahrzeugkupplung", "22",
                "AC Typ 2 Steckdose", "22")));

        assertThat(stations).singleElement().satisfies(station -> {
            assertThat(station.connectors()).isEmpty();
            assertThat(station.chargePoints()).hasSize(4);
            assertThat(station.chargePoints()).allSatisfy(chargePoint ->
                    assertThat(chargePoint.connectors())
                            .containsExactly(new SourceStation.SourceConnector("Type 2", new BigDecimal("22"), 1)));
        });
    }

    @Test
    @DisplayName("merges plugs repeated within one charge point into a quantity")
    void mergesIdenticalPlugsOfOneChargePoint() throws IOException {
        // A Ladepunkt with two identical sockets is one connector of quantity two — the merge that
        // used to happen across the whole site still happens inside a charge point.
        List<SourceStation> stations = parse(file(row("2b", "Betreiber", "Doppeldose", "In Betrieb",
                "Weg", "2", "10115", "Berlin", "52,5", "13,4", "",
                "\"AC Typ 2 Steckdose; AC Typ 2 Steckdose\"", "\"22; 22\"")));

        assertThat(stations).singleElement()
                .extracting(SourceStation::chargePoints, org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .singleElement()
                .extracting(chargePoint -> ((SourceStation.SourceChargePoint) chargePoint).connectors(),
                        org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .singleElement()
                .isEqualTo(new SourceStation.SourceConnector("Type 2", new BigDecimal("22"), 2));
    }

    @Test
    @DisplayName("pairs a multi-plug charge point with its per-plug ratings positionally")
    void zipsPlugsWithRatings() throws IOException {
        List<SourceStation> stations = parse(file(row("3", "Betreiber", "Gemischt", "In Betrieb",
                "Weg", "2", "10115", "Berlin", "52,5", "13,4", "",
                "\"AC Typ 2 Steckdose; AC Schuko\"", "\"22; 3,7\"")));

        assertThat(onlyChargePoint(stations).connectors())
                .extracting(SourceStation.SourceConnector::connectorType, SourceStation.SourceConnector::powerKw)
                .containsExactly(tuple("Type 2", new BigDecimal("22")), tuple("Schuko", new BigDecimal("3.7")));
    }

    @Test
    @DisplayName("applies a single rating to every plug of that charge point")
    void appliesASingleRatingToAllPlugs() throws IOException {
        List<SourceStation> stations = parse(file(row("4", "Betreiber", "Ein Wert", "In Betrieb",
                "Weg", "2", "10115", "Berlin", "52,5", "13,4", "",
                "\"DC Fahrzeugkupplung Typ Combo 2 (CCS); DC CHAdeMO\"", "50")));

        assertThat(onlyChargePoint(stations).connectors())
                .extracting(SourceStation.SourceConnector::connectorType, SourceStation.SourceConnector::powerKw)
                .containsExactly(tuple("CCS", new BigDecimal("50")), tuple("CHAdeMO", new BigDecimal("50")));
    }

    @Test
    @DisplayName("keeps each charge point's EVSE-ID, which live availability joins on")
    void keepsEvseIds() throws IOException {
        // 31 % of Ladeeinrichtungen in the 2026-07-28 edition carry one, in several spellings. The
        // parser stores them verbatim; normalizing for comparison is EvseIds' job.
        List<SourceStation> stations = parse(file(rowWithEvseIds("1140762",
                "AC Typ 2 Steckdose", "22", "DE*EBW*E913553*1",
                "AC Typ 2 Steckdose", "22", "DEAEWE009903")));

        assertThat(stations).singleElement()
                .extracting(SourceStation::chargePoints, org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .extracting("sourceChargePointId", "evseId")
                .containsExactly(tuple("1140762*1", "DE*EBW*E913553*1"), tuple("1140762*2", "DEAEWE009903"));
    }

    @Test
    @DisplayName("leaves the EVSE-ID null where the register publishes none")
    void toleratesMissingEvseIds() throws IOException {
        // The common case — 69,7 % of declared Ladepunkte. Such a charge point is still ingested; it
        // simply can never be matched to a live status.
        List<SourceStation> stations = parse(file(row("5555", "Betreiber", "Ohne ID", "In Betrieb",
                "Weg", "2", "10115", "Berlin", "52,5", "13,4", "", "AC Typ 2 Steckdose", "22")));

        assertThat(onlyChargePoint(stations)).satisfies(chargePoint -> {
            assertThat(chargePoint.sourceChargePointId()).isEqualTo("5555*1");
            assertThat(chargePoint.evseId()).isNull();
        });
    }

    @Test
    @DisplayName("names a site without a map label after its site description, then its operator")
    void fallsBackForTheDisplayName() throws IOException {
        // Blank for 63.290 of 113.385 rows in the real register, so this path is the common one.
        List<SourceStation> withSiteLabel = parse(file(row("5", "Stadtwerke", "", "In Betrieb",
                "Weg", "2", "10115", "Berlin", "52,5", "13,4", "Parkhaus Mitte", "AC Typ 2 Steckdose", "22")));
        List<SourceStation> withoutEither = parse(file(row("6", "Stadtwerke", "", "In Betrieb",
                "Weg", "2", "10115", "Berlin", "52,5", "13,4", "", "AC Typ 2 Steckdose", "22")));

        assertThat(withSiteLabel).singleElement().extracting(SourceStation::name).isEqualTo("Parkhaus Mitte");
        assertThat(withoutEither).singleElement().extracting(SourceStation::name).isEqualTo("Stadtwerke");
    }

    @Test
    @DisplayName("reads quoted fields containing delimiters and newlines")
    void readsQuotedFields() throws IOException {
        // 13.751 fields in the real register contain a newline; a line-oriented split would desynchronise
        // here and mis-assign every following column.
        List<SourceStation> stations = parse(file(row("7", "\"smopi\nMultitalent AG\"", "\"Nord; Süd\"", "In Betrieb",
                "Weg", "2", "10115", "Berlin", "52,5", "13,4", "", "AC Typ 2 Steckdose", "22")));

        assertThat(stations).singleElement().satisfies(station -> {
            assertThat(station.operatorName()).isEqualTo("smopi\nMultitalent AG");
            assertThat(station.name()).isEqualTo("Nord; Süd");
            assertThat(station.city()).isEqualTo("Berlin");
            assertThat(station.chargePoints()).hasSize(1);
        });
    }

    @Test
    @DisplayName("skips only rows that cannot be placed on a map")
    void skipsRowsWithoutCoordinates() throws IOException {
        List<SourceStation> stations = parse(file(
                row("8", "Betreiber", "Ohne Koordinaten", "In Betrieb",
                        "Weg", "2", "10115", "Berlin", "", "", "", "AC Typ 2 Steckdose", "22"),
                row("10", "Betreiber", "Gültig", "In Betrieb",
                        "Weg", "2", "10115", "Berlin", "52,5", "13,4", "", "AC Typ 2 Steckdose", "22")));

        assertThat(stations).extracting(SourceStation::sourceStationId).containsExactly("10");
    }

    @Test
    @DisplayName("keeps stations under maintenance, with their state attached")
    void mapsStatusToAvailability() throws IOException {
        // These used to be dropped, which silently deleted 21 real, registered stations from the map.
        List<SourceStation> stations = parse(file(
                row("11", "Betreiber", "In Betrieb", "In Betrieb",
                        "Weg", "2", "10115", "Berlin", "52,5", "13,4", "", "AC Typ 2 Steckdose", "22"),
                row("12", "Betreiber", "In Wartung", "In Wartung",
                        "Weg", "2", "10115", "Berlin", "52,5", "13,4", "", "AC Typ 2 Steckdose", "22"),
                row("13", "Betreiber", "Ohne Status", "",
                        "Weg", "2", "10115", "Berlin", "52,5", "13,4", "", "AC Typ 2 Steckdose", "22"),
                row("14", "Betreiber", "Neuer Status", "Im Bau",
                        "Weg", "2", "10115", "Berlin", "52,5", "13,4", "", "AC Typ 2 Steckdose", "22")));

        assertThat(stations)
                .extracting(SourceStation::sourceStationId, SourceStation::availabilityStatus)
                .containsExactly(
                        tuple("11", "OPERATIONAL"),
                        tuple("12", "MAINTENANCE"),
                        // Blank and unrecognised both mean "we do not know" — never "assume it works".
                        tuple("13", null),
                        tuple("14", null));
    }

    @Test
    @DisplayName("locates the header by its first column rather than by a fixed preamble length")
    void toleratesAChangedPreambleLength() throws IOException {
        String csv = file(row("20", "Betreiber", "Extra Vorspann", "In Betrieb",
                "Weg", "2", "10115", "Berlin", "52,5", "13,4", "", "AC Typ 2 Steckdose", "22"))
                .replace("Hinweis: ;;;\r\n", "Hinweis: ;;;\r\nEin zusätzlicher Hinweis;;;\r\n");

        assertThat(parse(csv)).singleElement()
                .extracting(SourceStation::sourceStationId).isEqualTo("20");
    }

    @Test
    @DisplayName("fails loudly when the register layout no longer has the expected header")
    void failsWithoutAHeader() {
        assertThatThrownBy(() -> parse("Irgendwas;anderes\r\nmit;Daten\r\n"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Ladeeinrichtungs-ID");
    }
}
