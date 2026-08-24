package de.joinside.evmap_service.sync.bnetza;

import de.joinside.evmap_service.sync.SourceStation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Covers the part of the adapter the parser tests cannot: finding the current download. The register's
 * file name is date-stamped, so this discovery is what breaks first when the download page changes.
 */
class BnetzaCsvSourceAdapterTests {
    private static final String INDEX_URL =
            "https://www.bundesnetzagentur.de/DE/Fachthemen/ElektrizitaetundGas/E-Mobilitaet/Ladesaeulenkarte/start.html";
    private static final String DATA_HOST =
            "https://data.bundesnetzagentur.de/Bundesnetzagentur/DE/Fachthemen/ElektrizitaetundGas/E-Mobilitaet/";

    /** The download page as published: both formats linked, CSV alongside XLSX. */
    private static final String INDEX_PAGE = """
            <html><body>
              <a href="%1$sLadesaeulenregister_BNetzA_2026-07-07.xlsx">Liste der Ladesäulen (xlsx / 28 MB)</a>
              <a href="%1$sLadesaeulenregister_BNetzA_2026-07-07.csv">Liste der Ladesäulen (csv / 49 MB)</a>
            </body></html>
            """.formatted(DATA_HOST);

    private static final String CSV = ("﻿Ladesäulenregister Bundesnetzagentur;;\r\n"
            + "Letzte Aktualisierung vom: 07.07.2026;;\r\n"
            + "Ladeeinrichtungs-ID;Betreiber;Anzeigename (Karte);Status;Straße;Hausnummer;Postleitzahl;Ort;"
            + "Breitengrad;Längengrad;Standortbezeichnung;Steckertypen1;Nennleistung Stecker1\r\n"
            + "1010338;Albwerk GmbH;Albwerk Heroldstatt;In Betrieb;Am Berg;1;72535;Heroldstatt;"
            + "48,442398;9,659075;;AC Typ 2 Steckdose;22\r\n");

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    private BnetzaCsvSourceAdapter adapter(BnetzaProperties properties) {
        return new BnetzaCsvSourceAdapter(properties, builder.build());
    }

    private static BnetzaProperties properties(String csvUrl) {
        return new BnetzaProperties(true, INDEX_URL, csvUrl, Duration.ofSeconds(5));
    }

    private static List<SourceStation> drain(Stream<SourceStation> stations) {
        try (stations) {
            return stations.toList();
        }
    }

    @Test
    @DisplayName("finds the current CSV on the download page and ingests it")
    void discoversAndIngestsTheCurrentEdition() {
        server.expect(requestTo(INDEX_URL)).andRespond(withSuccess(INDEX_PAGE, MediaType.TEXT_HTML));
        // The CSV link, not the XLSX one that sits next to it.
        server.expect(requestTo(DATA_HOST + "Ladesaeulenregister_BNetzA_2026-07-07.csv"))
                .andRespond(withSuccess(CSV, MediaType.TEXT_PLAIN));

        List<SourceStation> stations = drain(adapter(properties("")).fetchStations());

        server.verify();
        assertThat(stations).singleElement().satisfies(station -> {
            assertThat(station.sourceStationId()).isEqualTo("1010338");
            assertThat(station.countryCode()).isEqualTo("DE");
            assertThat(station.lastUpdatedAt()).isEqualTo(Instant.parse("2026-07-07T00:00:00Z"));
        });
    }

    @Test
    @DisplayName("takes the newest edition when the page still links older ones")
    void prefersTheNewestEdition() {
        String withAnOlderLink = INDEX_PAGE.replace("</body>",
                "<a href=\"" + DATA_HOST + "Ladesaeulenregister_BNetzA_2026-06-02.csv\">Vormonat</a></body>");
        server.expect(requestTo(INDEX_URL)).andRespond(withSuccess(withAnOlderLink, MediaType.TEXT_HTML));
        server.expect(requestTo(DATA_HOST + "Ladesaeulenregister_BNetzA_2026-07-07.csv"))
                .andRespond(withSuccess(CSV, MediaType.TEXT_PLAIN));

        assertThat(drain(adapter(properties("")).fetchStations())).hasSize(1);
        server.verify();
    }

    @Test
    @DisplayName("skips discovery when an edition is pinned by configuration")
    void honoursAPinnedUrl() {
        String pinned = DATA_HOST + "Ladesaeulenregister_BNetzA_2026-06-02.csv";
        server.expect(requestTo(pinned)).andRespond(withSuccess(CSV, MediaType.TEXT_PLAIN));

        List<SourceStation> stations = drain(adapter(properties(pinned)).fetchStations());

        // No call to the download page at all: one request, to the pinned file.
        server.verify();
        assertThat(stations).singleElement()
                // The edition still comes from the file's own preamble, not from the pinned file name.
                .extracting(SourceStation::lastUpdatedAt).isEqualTo(Instant.parse("2026-07-07T00:00:00Z"));
    }

    @Test
    @DisplayName("says what to do when the download page no longer links a register")
    void failsWithAnActionableMessage() {
        server.expect(requestTo(INDEX_URL))
                .andRespond(withSuccess("<html><body>Umgezogen.</body></html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> adapter(properties("")).fetchStations())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("evmap.sync.bnetza.csv-url");
    }

    @Test
    @DisplayName("reports itself disabled, so the run never asks it to fetch")
    void canBeDisabled() {
        BnetzaProperties disabled = new BnetzaProperties(false, INDEX_URL, "", Duration.ofSeconds(5));

        // The flag is checked by SyncJob rather than inside fetchStations(): one place decides which
        // sources take part, and a disabled source is reported once instead of per adapter.
        assertThat(adapter(disabled).enabled()).isFalse();
        assertThat(adapter(properties("")).enabled()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("names the source it writes, matching the records it emits")
    void namesItsSource() {
        assertThat(adapter(properties("")).source()).isEqualTo("BNetzA");
    }
}
