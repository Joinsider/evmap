package de.joinside.evmap_service.availability.irve;

import de.joinside.evmap_service.availability.ChargePointAvailability;
import de.joinside.evmap_service.availability.GeoBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IrveDynamicAvailabilityProviderTests {
    private static final String CSV_URL = "https://www.data.gouv.fr/api/1/datasets/r/irve-dynamique";
    private static final GeoBounds PARIS = new GeoBounds(48.85, 2.34, 48.86, 2.36);
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final String CSV = """
            id_pdc_itinerance,etat_pdc,occupation_pdc,horodatage,etat_prise_type_2,etat_prise_type_combo_ccs,etat_prise_type_chademo,etat_prise_type_ef
            FRFASE3336808,en_service,libre,2026-09-28 11:45:01+00:00,,fonctionnel,,
            FRATLE105612,en_service,occupe,2026-09-28 11:50:00+00:00,,,,
            """;

    /** A clock the test moves forward, to cross the refresh interval without sleeping. */
    private static final class MovableClock extends Clock {
        private Instant now = NOW;

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private MockRestServiceServer server;
    private MovableClock clock;
    private IrveDynamicAvailabilityProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        clock = new MovableClock();
        provider = new IrveDynamicAvailabilityProvider(properties(), builder.build(), clock);
    }

    private static IrveDynamicProperties properties() {
        return new IrveDynamicProperties(true, CSV_URL, List.of("FR"), Duration.ofMinutes(1),
                Duration.ofHours(72), Duration.ofMinutes(10), Duration.ofSeconds(15));
    }

    @Test
    @DisplayName("answers any area with the whole country, since the file carries no coordinates")
    void answersWithTheWholeFile() {
        server.expect(once(), requestTo(CSV_URL))
                .andExpect(header(HttpHeaders.ACCEPT_ENCODING, "gzip"))
                .andRespond(withSuccess(CSV, MediaType.parseMediaType("text/csv")));

        assertThat(provider.fetch(PARIS)).extracting(ChargePointAvailability::evseId)
                .containsExactlyInAnyOrder("FRFASE3336808", "FRATLE105612");
        assertThat(provider.covers("FR")).isTrue();
        assertThat(provider.covers("DE")).isFalse();
    }

    @Test
    @DisplayName("unpacks the gzip the proxy sends when asked for it")
    void readsGzip() throws IOException {
        server.expect(requestTo(CSV_URL)).andRespond(withSuccess(gzip(CSV), MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_ENCODING, "gzip"));

        assertThat(provider.fetch(PARIS)).hasSize(2);
    }

    @Test
    @DisplayName("downloads once per refresh interval, not once per request")
    void reusesTheDownload() {
        server.expect(once(), requestTo(CSV_URL)).andRespond(withSuccess(CSV, MediaType.parseMediaType("text/csv")));
        provider.fetch(PARIS);
        clock.advance(Duration.ofSeconds(30));
        assertThat(provider.fetch(PARIS)).hasSize(2);
        server.verify();

        server.reset();
        server.expect(once(), requestTo(CSV_URL)).andRespond(withSuccess(CSV, MediaType.parseMediaType("text/csv")));
        clock.advance(Duration.ofSeconds(31));
        provider.fetch(PARIS);
        server.verify();
    }

    @Test
    @DisplayName("keeps the last good copy through a failed refresh, then gives up after stale-after")
    void survivesAnOutage() {
        server.expect(once(), requestTo(CSV_URL)).andRespond(withSuccess(CSV, MediaType.parseMediaType("text/csv")));
        provider.fetch(PARIS);

        server.reset();
        server.expect(once(), requestTo(CSV_URL)).andRespond(withServerError());
        clock.advance(Duration.ofMinutes(2));
        assertThat(provider.fetch(PARIS)).as("previous copy still served").hasSize(2);
        server.verify();

        server.reset();
        server.expect(once(), requestTo(CSV_URL)).andRespond(withServerError());
        clock.advance(Duration.ofMinutes(9));
        assertThat(provider.fetch(PARIS)).as("past stale-after, France reads as unknown").isEmpty();
    }

    @Test
    @DisplayName("an unreachable source answers nothing rather than throwing")
    void failsQuietly() {
        server.expect(requestTo(CSV_URL)).andRespond(withServerError());
        assertThat(provider.fetch(PARIS)).isEmpty();
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }
}
