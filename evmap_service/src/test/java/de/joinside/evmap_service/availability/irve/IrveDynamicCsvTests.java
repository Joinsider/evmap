package de.joinside.evmap_service.availability.irve;

import de.joinside.evmap_service.availability.ChargePointAvailability;
import de.joinside.evmap_service.availability.LiveAvailability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Fixture rows are copied from the 2026-09-28 national consolidation
 * ({@code consolidation-nationale-irve-dynamique}), keeping the three timestamp spellings and the
 * duplicate ids it actually contains.
 */
class IrveDynamicCsvTests {
    private static final String HEADER = "id_pdc_itinerance,etat_pdc,occupation_pdc,horodatage,"
            + "etat_prise_type_2,etat_prise_type_combo_ccs,etat_prise_type_chademo,etat_prise_type_ef\n";
    private static final Instant LONG_AGO = Instant.parse("2020-01-01T00:00:00Z");

    private static List<ChargePointAvailability> parse(String rows, Instant notBefore) throws IOException {
        return IrveDynamicCsv.parse(new StringReader(HEADER + rows), notBefore);
    }

    @Test
    @DisplayName("normalizes ids and maps both status axes onto the client vocabulary")
    void readsRows() throws IOException {
        List<ChargePointAvailability> parsed = parse("""
                FRFASE3336808,en_service,libre,2026-09-28 03:45:01.754000+00:00,,fonctionnel,fonctionnel,
                FR*ATL*E105612,en_service,occupe,2026-09-28T06:07:23+02:00,inconnu,fonctionnel,inconnu,
                FRVCE9009472,hors_service,inconnu,2026-09-16 09:05:10.566659+00:00,,,,
                """, LONG_AGO);

        assertThat(parsed)
                .extracting(ChargePointAvailability::evseId, ChargePointAvailability::status, ChargePointAvailability::observedAt)
                .containsExactlyInAnyOrder(
                        tuple("FRFASE3336808", LiveAvailability.AVAILABLE, Instant.parse("2026-09-28T03:45:01.754Z")),
                        // The starred spelling arrives normalized, as the stored id does.
                        tuple("FRATLE105612", LiveAvailability.OCCUPIED, Instant.parse("2026-09-28T04:07:23Z")),
                        tuple("FRVCE9009472", LiveAvailability.OUT_OF_ORDER, Instant.parse("2026-09-16T09:05:10.566659Z")));
    }

    @Test
    @DisplayName("keeps the newest row when one charge point is published twice")
    void newestDuplicateWins() throws IOException {
        List<ChargePointAvailability> parsed = parse("""
                FRS14E1,en_service,occupe,2026-09-28 10:00:00+00:00,,,,
                FRS14E1,en_service,libre,2026-09-28 10:05:00+00:00,,,,
                FRS14E1,en_service,occupe,2026-09-28 09:00:00+00:00,,,,
                """, LONG_AGO);

        assertThat(parsed).singleElement()
                .extracting(ChargePointAvailability::status).isEqualTo(LiveAvailability.AVAILABLE);
    }

    @Test
    @DisplayName("drops rows older than max-age, because a dead feed's 'free' is the wrong answer")
    void dropsStaleRows() throws IOException {
        List<ChargePointAvailability> parsed = parse("""
                FRETIE34307A15,en_service,libre,2020-12-29 14:39:41,,,,
                FRFASE3336808,en_service,libre,2026-09-28 03:45:01+00:00,,,,
                """, Instant.parse("2026-09-25T00:00:00Z"));

        assertThat(parsed).extracting(ChargePointAvailability::evseId).containsExactly("FRFASE3336808");
    }

    @Test
    @DisplayName("skips rows without an id or a readable timestamp rather than failing the file")
    void skipsUnusableRows() throws IOException {
        List<ChargePointAvailability> parsed = parse("""
                ,en_service,libre,2026-09-28 03:45:01+00:00,,,,
                FRNOTIME1,en_service,libre,yesterday,,,,
                FRGOOD1,en_service,libre,2026-09-28 03:45:01+00:00,,,,
                """, LONG_AGO);

        assertThat(parsed).extracting(ChargePointAvailability::evseId).containsExactly("FRGOOD1");
    }

    @Test
    @DisplayName("reads an offset-less timestamp as French local time")
    void offsetLessIsParis() {
        // 14:39 in Paris in winter is 13:39 UTC.
        assertThat(IrveDynamicCsv.timestamp("2020-12-29 14:39:41")).isEqualTo(Instant.parse("2020-12-29T13:39:41Z"));
        assertThat(IrveDynamicCsv.timestamp("2026-09-28T16:12:12+02:00")).isEqualTo(Instant.parse("2026-09-28T14:12:12Z"));
        assertThat(IrveDynamicCsv.timestamp("2026-09-28T14:12:12Z")).isEqualTo(Instant.parse("2026-09-28T14:12:12Z"));
        assertThat(IrveDynamicCsv.timestamp("")).isNull();
    }

    @ParameterizedTest(name = "{0} + {1} → {2}")
    @DisplayName("broken beats everything, occupied is believed, free only when also in service")
    @CsvSource({
            "hors_service, occupe,  OUT_OF_ORDER",
            "hors_service, libre,   OUT_OF_ORDER",
            "en_service,   occupe,  OCCUPIED",
            "inconnu,      occupe,  OCCUPIED",
            "en_service,   reserve, OCCUPIED",
            "en_service,   libre,   AVAILABLE",
            "inconnu,      libre,   UNKNOWN",
            "en_service,   inconnu, UNKNOWN",
            "inconnu,      inconnu, UNKNOWN",
    })
    void mapsStatus(String etat, String occupation, String expected) {
        assertThat(IrveDynamicCsv.toLiveAvailability(etat, occupation)).isEqualTo(expected);
    }
}
