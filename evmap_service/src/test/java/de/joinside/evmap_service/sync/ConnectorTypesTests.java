package de.joinside.evmap_service.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectorTypesTests {

    @DisplayName("every connector label the BNetzA register publishes maps onto the canonical vocabulary")
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            // The complete distinct set from the 2026-07-07 edition, most frequent first.
            "AC Typ 2 Steckdose                     | Type 2",
            "DC Fahrzeugkupplung Typ Combo 2 (CCS)  | CCS",
            "AC Typ 2 Fahrzeugkupplung              | Type 2",
            "AC Schuko                              | Schuko",
            "DC CHAdeMO                             | CHAdeMO",
            "DC Megawatt Charging System (MCS)      | MCS",
            "AC Typ 1 Steckdose                     | Type 1",
            "AC CEE 5-polig                         | CEE",
            "AC CEE 3-polig                         | CEE",
            "DC Tesla Fahrzeugkupplung (Typ 2)      | Tesla",
    })
    void mapsBnetzaLabels(String label, String expected) {
        assertThat(ConnectorTypes.normalize(label)).isEqualTo(expected);
    }

    @DisplayName("Open Charge Map connection titles map onto the same vocabulary")
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "Type 2 (Socket Only)          | Type 2",
            "Type 2 (Tethered Connector)   | Type 2",
            "CCS (Type 2)                  | CCS",
            "CCS (Type 1)                  | CCS",
            "CHAdeMO                       | CHAdeMO",
            "Type 1 (J1772)                | Type 1",
            "Tesla (Model S/X)             | Tesla",
            "Tesla Supercharger            | Tesla",
            "NACS / Tesla Supercharger     | Tesla",
            "CEE 7/4 - Schuko - Type F     | Schuko",
            "Blue Commando (2P+E)          | CEE",
            "IEC 60309 5-pin               | CEE",
            // The rest of the titles actually present in German OCM data, verified 2026-07-28.
            "CEE 5 Pin                     | CEE",
            "CEE 3 Pin                     | CEE",
            "CEE+ 7 Pin                    | CEE",
            "Tesla (Roadster)              | Tesla",
            "Europlug 2-Pin (CEE 7/16)     | Schuko",
    })
    void mapsOpenChargeMapTitles(String title, String expected) {
        assertThat(ConnectorTypes.normalize(title)).isEqualTo(expected);
    }

    @Test
    @DisplayName("the more specific standard wins when a label names two")
    void prefersTheSpecificStandard() {
        // Every one of these mentions Type 2 as well; reading them as Type 2 would file a DC fast
        // charger under an AC filter, which is the failure this ordering exists to prevent.
        assertThat(ConnectorTypes.normalize("DC Fahrzeugkupplung Typ Combo 2 (CCS)")).isEqualTo(ConnectorTypes.CCS);
        assertThat(ConnectorTypes.normalize("DC Tesla Fahrzeugkupplung (Typ 2)")).isEqualTo(ConnectorTypes.TESLA);
        assertThat(ConnectorTypes.normalize("CEE 7/4 - Schuko - Type F")).isEqualTo(ConnectorTypes.SCHUKO);
    }

    @Test
    @DisplayName("a domestic socket is not filed under the industrial CEE connector")
    void doesNotConfuseDomesticSocketsWithIndustrialCee() {
        // Both carry a CEE designation but are ordinary 230 V household sockets. A driver filtering
        // for CEE wants the blue/red industrial plug, not something they need a domestic adapter for.
        assertThat(ConnectorTypes.normalize("Europlug 2-Pin (CEE 7/16)")).isEqualTo(ConnectorTypes.SCHUKO);
        assertThat(ConnectorTypes.normalize("CEE 7/4 - Schuko - Type F")).isEqualTo(ConnectorTypes.SCHUKO);
        // ...while the genuine industrial ones still are CEE.
        assertThat(ConnectorTypes.normalize("CEE 5 Pin")).isEqualTo(ConnectorTypes.CEE);
        assertThat(ConnectorTypes.normalize("AC CEE 5-polig")).isEqualTo(ConnectorTypes.CEE);
    }

    @Test
    @DisplayName("an unrecognised label survives as-is rather than being dropped")
    void keepsUnknownLabels() {
        assertThat(ConnectorTypes.normalize("  Inductive charging pad  ")).isEqualTo("Inductive charging pad");
    }

    @Test
    @DisplayName("an unrecognised label is clipped to the column width")
    void clipsOverlongLabels() {
        assertThat(ConnectorTypes.normalize("x".repeat(200))).hasSize(64);
    }

    @DisplayName("labels carrying no information yield nothing to store")
    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "Unknown", "unknown"})
    void dropsEmptyAndUnknown(String label) {
        assertThat(ConnectorTypes.normalize(label)).isNull();
    }

    @Test
    void toleratesNull() {
        assertThat(ConnectorTypes.normalize(null)).isNull();
    }
}
