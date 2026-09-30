package de.joinside.evmap_service.sync.ch;

import de.joinside.evmap_service.sync.SourceStation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Covers what the parser tests cannot: that the configured URL is fetched, that the body is read
 * whether it arrives as plain JSON or still gzipped (the server labels its gzip object
 * {@code Content-Encoding: gzip}, and whether the label is honoured depends on the HTTP client), and
 * that a failed download leaves nothing half-done.
 */
class DiemoSourceAdapterTests {
    private static final String URL = "https://data.geo.admin.ch/register.json";
    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

    private static final String JSON = """
            {"EVSEData": [{"OperatorID": "CH*CCC", "OperatorName": "Move", "EVSEDataRecord": [
              {"EvseID": "CH*CCI*E22078", "Accessibility": "Free publicly accessible",
               "Address": {"Street": "Esplanade des Particules", "City": "Meyrin", "PostalCode": "1217", "Country": "CHE"},
               "GeoCoordinates": {"Google": "46.23432 6.055602"}, "Plugs": ["Type 2 Outlet"],
               "ChargingFacilities": [{"power": "22.0"}], "ChargingStationNames": null}
            ]}]}
            """;

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    private DiemoSourceAdapter adapter() {
        return new DiemoSourceAdapter(new DiemoProperties(true, URL, Duration.ofSeconds(5)), builder.build(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static byte[] gzip(byte[] content) {
        try (var bytes = new ByteArrayOutputStream(); var gzip = new GZIPOutputStream(bytes)) {
            gzip.write(content);
            gzip.finish();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static List<SourceStation> drain(Stream<SourceStation> stations) {
        try (stations) {
            return stations.toList();
        }
    }

    private void assertReadsTheRegister(byte[] body) {
        server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<SourceStation> stations = drain(adapter().fetchStations());

        assertThat(stations).singleElement().satisfies(station -> {
            assertThat(station.source()).isEqualTo("DIEMO");
            assertThat(station.city()).isEqualTo("Meyrin");
            assertThat(station.lastUpdatedAt()).isEqualTo(NOW);
        });
        server.verify();
    }

    @Test
    @DisplayName("names its source and fetches the configured URL")
    void fetchesTheConfiguredUrl() {
        assertThat(adapter().source()).isEqualTo("DIEMO");
        assertReadsTheRegister(JSON.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("reads a gzip body, which is what the BFE's server delivers to a client that ignores the label")
    void readsGzip() {
        assertReadsTheRegister(gzip(JSON.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("fails the source on a server error instead of returning an empty register")
    void failsOnServerError() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> adapter().fetchStations())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("503");
    }

    @Test
    @DisplayName("fails the source when the body is not the register")
    void failsOnAWrongDocument() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"error\": \"moved\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter().fetchStations())
                .isInstanceOf(UncheckedIOException.class).hasMessageContaining("EVSEData");
    }

    @Test
    @DisplayName("can be switched off")
    void canBeDisabled() {
        var disabled = new DiemoSourceAdapter(new DiemoProperties(false, URL, Duration.ofSeconds(5)), builder.build(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(disabled.enabled()).isFalse();
    }
}
