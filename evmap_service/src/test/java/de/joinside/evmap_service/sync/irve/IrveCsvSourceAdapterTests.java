package de.joinside.evmap_service.sync.irve;

import de.joinside.evmap_service.sync.SourceStation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Covers what the parser tests cannot: that the configured resource is actually fetched, and that a
 * failed download leaves nothing half-done. Unlike BNetzA there is no discovery step to test — the
 * data.gouv.fr resource id is stable and redirects to the current edition on its own.
 */
class IrveCsvSourceAdapterTests {
    private static final String CSV_URL = "https://www.data.gouv.fr/api/1/datasets/r/eb76d20a";

    private static final String CSV = """
            nom_operateur,id_station_itinerance,nom_station,adresse_station,puissance_nominale,\
            prise_type_ef,prise_type_2,prise_type_combo_ccs,prise_type_chademo,prise_type_autre,\
            condition_acces,date_maj,last_modified,consolidated_longitude,consolidated_latitude,\
            consolidated_code_postal,consolidated_commune
            Izivia,FRS01P0001,Mairie de Haguenau,"93 route de Bitche, 67506 Haguenau",22,\
            false,true,false,false,false,Accès libre,2026-07-27,2026-07-28T21:00:49.553000+00:00,\
            7.762694,48.825613,67500,Haguenau
            """;

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    private IrveCsvSourceAdapter adapter(IrveProperties properties) {
        return new IrveCsvSourceAdapter(properties, builder.build());
    }

    private static IrveProperties properties() {
        return new IrveProperties(true, CSV_URL, Duration.ofSeconds(5));
    }

    private static List<SourceStation> drain(Stream<SourceStation> stations) {
        try (stations) {
            return stations.toList();
        }
    }

    @Test
    @DisplayName("downloads the configured consolidation and maps it")
    void downloadsAndMaps() {
        server.expect(requestTo(CSV_URL)).andRespond(withSuccess(CSV, MediaType.TEXT_PLAIN));

        assertThat(drain(adapter(properties()).fetchStations()))
                .singleElement()
                .satisfies(station -> {
                    assertThat(station.source()).isEqualTo("IRVE");
                    assertThat(station.sourceStationId()).isEqualTo("FRS01P0001");
                    assertThat(station.countryCode()).isEqualTo("FR");
                });
        server.verify();
    }

    @Test
    @DisplayName("names the source it writes, matching the records it emits")
    void namesItsSource() {
        assertThat(adapter(properties()).source()).isEqualTo("IRVE");
    }

    @Test
    @DisplayName("reports itself disabled, so the run never asks it to fetch")
    void canBeDisabled() {
        assertThat(adapter(new IrveProperties(false, CSV_URL, Duration.ofSeconds(5))).enabled()).isFalse();
        assertThat(adapter(properties()).enabled()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("fails loudly on a download that does not arrive, rather than ingesting nothing quietly")
    void failsOnADeadDownload() {
        server.expect(requestTo(CSV_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        // The run contains this in SourceAdapterRun; silently returning an empty stream instead would
        // look identical to "France genuinely has no charge points today".
        assertThatThrownBy(() -> adapter(properties()).fetchStations())
                .hasMessageContaining("404");
    }

    @Test
    @DisplayName("surfaces a changed schema instead of silently ingesting nothing")
    void failsOnAChangedSchema() {
        server.expect(requestTo(CSV_URL))
                .andRespond(withSuccess("colonne_a,colonne_b\n1,2\n", MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> adapter(properties()).fetchStations())
                .hasMessageContaining("id_station_itinerance");
    }
}
