package de.joinside.evmap_service.sync.ocm;

import de.joinside.evmap_service.sync.AvailabilityStatus;
import de.joinside.evmap_service.sync.SourceStation;
import de.joinside.evmap_service.sync.SyncStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenChargeMapSourceAdapterTests {
    private static final String BASE_URL = "https://api.openchargemap.io/v3";

    private static final String REFERENCE_DATA = """
            {
              "ConnectionTypes": [
                {"ID": 25, "Title": "Type 2 (Socket Only)"},
                {"ID": 33, "Title": "CCS (Type 2)"},
                {"ID": 2,  "Title": "CHAdeMO"},
                {"ID": 0,  "Title": "Unknown"}
              ],
              "Operators": [
                {"ID": 23, "Title": "Allego"},
                {"ID": 42, "Title": "Fastned"}
              ],
              "StatusTypes": [
                {"ID": 0, "Title": "Unknown"},
                {"ID": 10, "Title": "Currently Available (Automated Status)", "IsOperational": true},
                {"ID": 20, "Title": "Currently In Use (Automated Status)", "IsOperational": true},
                {"ID": 30, "Title": "Temporarily Unavailable", "IsOperational": true},
                {"ID": 50, "Title": "Operational", "IsOperational": true},
                {"ID": 75, "Title": "Partly Operational (Mixed)", "IsOperational": true},
                {"ID": 100, "Title": "Not Operational", "IsOperational": false},
                {"ID": 150, "Title": "Planned For Future Date", "IsOperational": false},
                {"ID": 200, "Title": "Removed (Decommissioned)", "IsOperational": false},
                {"ID": 210, "Title": "Removed (Duplicate Listing)", "IsOperational": false},
                {"ID": 999, "Title": "Something New", "IsOperational": false}
              ]
            }
            """;

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private RecordingSyncState syncState;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        syncState = new RecordingSyncState();
    }

    /** In-memory watermarks, so incremental behaviour is observable without a database. */
    private static final class RecordingSyncState implements SyncStateStore {
        private final Map<String, Instant> marks = new HashMap<>();

        @Override
        public Optional<Instant> watermark(String source, String scope) {
            return Optional.ofNullable(marks.get(source + "/" + scope));
        }

        @Override
        public void recordWatermark(String source, String scope, Instant watermark) {
            marks.put(source + "/" + scope, watermark);
        }
    }

    /** Production wiring over a mock transport, so the key and caller identity are the real ones. */
    private OpenChargeMapSourceAdapter adapter(OpenChargeMapProperties properties) {
        return new OpenChargeMapSourceAdapter(properties,
                OpenChargeMapSourceAdapter.configure(properties, builder).build(), syncState);
    }

    private static OpenChargeMapProperties properties(List<String> countries, int pageSize) {
        return properties(countries, pageSize, false);
    }

    private static OpenChargeMapProperties properties(List<String> countries, int pageSize, boolean incremental) {
        return new OpenChargeMapProperties(true, BASE_URL, "test-key", countries, pageSize,
                Duration.ZERO, 200, true, Duration.ofSeconds(5), "evmap-tests",
                incremental, Duration.ofDays(2), Duration.ofDays(7));
    }

    private static String poi(long id, int connectionTypeId) {
        return """
                {
                  "ID": %d,
                  "OperatorID": 23,
                  "StatusTypeID": 50,
                  "DateLastStatusUpdate": "2026-05-01T10:00:00Z",
                  "DateCreated": "2020-01-01T00:00:00Z",
                  "AddressInfo": {
                    "Title": "Site %d", "AddressLine1": "Hauptstr. 1", "Town": "Berlin",
                    "Postcode": "10115", "Latitude": 52.5, "Longitude": 13.4
                  },
                  "Connections": [{"ConnectionTypeID": %d, "PowerKW": 50, "Quantity": 2}]
                }
                """.formatted(id, id, connectionTypeId);
    }

    private static String withStatus(long id, int statusTypeId) {
        return poi(id, 25).replace("\"StatusTypeID\": 50", "\"StatusTypeID\": " + statusTypeId);
    }

    private void expectReferenceData() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/referencedata/")))
                .andRespond(withSuccess(REFERENCE_DATA, MediaType.APPLICATION_JSON));
    }

    private void expectPoiPage(long greaterThanId, String body) {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/poi/")))
                .andExpect(queryParam("greaterthanid", String.valueOf(greaterThanId)))
                .andExpect(queryParam("sortby", "id_asc"))
                .andExpect(queryParam("compact", "true"))
                .andExpect(queryParam("opendata", "true"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("resolves compact reference ids into connector types and operator names")
    void resolvesReferenceData() {
        expectReferenceData();
        expectPoiPage(0, "[" + poi(101, 33) + "]");

        List<SourceStation> stations = adapter(properties(List.of("DE"), 500)).fetchStations().toList();

        server.verify();
        assertThat(stations).singleElement().satisfies(station -> {
            assertThat(station.source()).isEqualTo("OCM");
            assertThat(station.sourceStationId()).isEqualTo("101");
            assertThat(station.name()).isEqualTo("Site 101");
            assertThat(station.operatorName()).isEqualTo("Allego");
            assertThat(station.street()).isEqualTo("Hauptstr. 1");
            assertThat(station.city()).isEqualTo("Berlin");
            // Taken from the request, not the payload: compact results carry only a numeric CountryID.
            assertThat(station.countryCode()).isEqualTo("DE");
            assertThat(station.lastUpdatedAt()).isEqualTo(Instant.parse("2026-05-01T10:00:00Z"));
            assertThat(station.connectors()).containsExactly(
                    new SourceStation.SourceConnector("CCS", new BigDecimal("50"), 2));
        });
    }

    @Test
    @DisplayName("pages by ascending id and stops on the first short page")
    void pagesByKeyset() {
        expectReferenceData();
        // A full page must be followed by another request starting above its last id...
        expectPoiPage(0, "[" + poi(101, 25) + "," + poi(102, 25) + "]");
        // ...and a short page ends the country without spending a request to prove the next is empty.
        expectPoiPage(102, "[" + poi(103, 25) + "]");

        List<SourceStation> stations = adapter(properties(List.of("DE"), 2)).fetchStations().toList();

        server.verify();
        assertThat(stations).extracting(SourceStation::sourceStationId).containsExactly("101", "102", "103");
    }

    @Test
    @DisplayName("crawls each configured country separately")
    void crawlsEachCountry() {
        expectReferenceData();
        server.expect(queryParam("countrycode", "DE"))
                .andRespond(withSuccess("[" + poi(1, 25) + "]", MediaType.APPLICATION_JSON));
        server.expect(queryParam("countrycode", "NL"))
                .andRespond(withSuccess("[" + poi(2, 25) + "]", MediaType.APPLICATION_JSON));

        List<SourceStation> stations = adapter(properties(List.of("de", " nl "), 500)).fetchStations().toList();

        server.verify();
        assertThat(stations)
                .extracting(SourceStation::sourceStationId, SourceStation::countryCode)
                .containsExactly(tuple("1", "DE"), tuple("2", "NL"));
    }

    @Test
    @DisplayName("sends the API key and identifies the caller")
    void authenticatesAndIdentifiesItself() {
        server.expect(header("X-API-Key", "test-key"))
                .andExpect(header("User-Agent", org.hamcrest.Matchers.startsWith("EVMap/")))
                .andRespond(withSuccess(REFERENCE_DATA, MediaType.APPLICATION_JSON));
        server.expect(queryParam("client", "evmap-tests"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        adapter(properties(List.of("DE"), 500)).fetchStations().toList();

        server.verify();
    }

    @Test
    @DisplayName("skips itself without an API key instead of failing the whole sync run")
    void skipsWithoutApiKey() {
        // A deployment may legitimately run BNetzA only; failing here would take German coverage down too.
        OpenChargeMapProperties noKey = new OpenChargeMapProperties(true, BASE_URL, "  ", List.of("DE"), 500,
                Duration.ZERO, 200, true, Duration.ofSeconds(5), "evmap-tests",
                false, Duration.ofDays(2), Duration.ofDays(7));

        assertThat(adapter(noKey).fetchStations()).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("ingests sites unresolved rather than failing when reference data is unavailable")
    void survivesMissingReferenceData() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/referencedata/")))
                .andRespond(withServerError());
        expectPoiPage(0, "[" + poi(101, 33) + "]");

        List<SourceStation> stations = adapter(properties(List.of("DE"), 500)).fetchStations().toList();

        server.verify();
        assertThat(stations).singleElement().satisfies(station -> {
            assertThat(station.sourceStationId()).isEqualTo("101");
            // The site is still mappable; only the unresolvable connector and operator are missing.
            assertThat(station.connectors()).isEmpty();
            assertThat(station.operatorName()).isNull();
        });
    }

    @Test
    @DisplayName("drops sites that cannot be placed on a map, and connectors that say nothing")
    void dropsUnusableData() {
        expectReferenceData();
        String withoutCoordinates = """
                {"ID": 200, "AddressInfo": {"Title": "No position", "Town": "Berlin"}}
                """;
        // ConnectionTypeID 0 is OCM's literal "Unknown" — a connector row carrying no information.
        String unknownConnector = """
                {"ID": 201, "OperatorID": 42,
                 "AddressInfo": {"Title": "Known position", "Latitude": 52.5, "Longitude": 13.4},
                 "Connections": [{"ConnectionTypeID": 0, "Quantity": 1}]}
                """;
        expectPoiPage(0, "[" + withoutCoordinates + "," + unknownConnector + "]");

        List<SourceStation> stations = adapter(properties(List.of("DE"), 500)).fetchStations().toList();

        server.verify();
        assertThat(stations).singleElement().satisfies(station -> {
            assertThat(station.sourceStationId()).isEqualTo("201");
            assertThat(station.connectors()).isEmpty();
        });
    }

    @Test
    @DisplayName("maps OCM's operational flag onto the shared availability vocabulary")
    void mapsAvailability() {
        expectReferenceData();
        String outOfService = poi(300, 25).replace("\"StatusTypeID\": 50", "\"StatusTypeID\": 100");
        String unknownStatus = poi(301, 25).replace("\"StatusTypeID\": 50", "\"StatusTypeID\": 0");
        expectPoiPage(0, "[" + poi(299, 25) + "," + outOfService + "," + unknownStatus + "]");

        List<SourceStation> stations = adapter(properties(List.of("DE"), 500)).fetchStations().toList();

        server.verify();
        assertThat(stations)
                .extracting(SourceStation::sourceStationId, SourceStation::availabilityStatus)
                .containsExactly(
                        tuple("299", AvailabilityStatus.OPERATIONAL),
                        tuple("300", AvailabilityStatus.OUT_OF_SERVICE),
                        // Status type 0 carries no IsOperational flag: unknown, not "assume it works".
                        tuple("301", null));
    }

    @Test
    @DisplayName("does not trust IsOperational for a station OCM calls temporarily unavailable")
    void temporarilyUnavailableIsNotOperational() {
        // OCM flags status type 30 IsOperational=true. Reading that flag literally would advertise a
        // station that is currently out of service as usable — verified against live data 2026-07-28.
        expectReferenceData();
        expectPoiPage(0, "[" + withStatus(400, 30) + "," + withStatus(401, 20) + ","
                + withStatus(402, 75) + "," + withStatus(403, 999) + "]");

        List<SourceStation> stations = adapter(properties(List.of("DE"), 500)).fetchStations().toList();

        server.verify();
        assertThat(stations)
                .extracting(SourceStation::sourceStationId, SourceStation::availabilityStatus)
                .containsExactly(
                        tuple("400", AvailabilityStatus.MAINTENANCE),
                        // Occupied is not broken; real-time occupancy is a non-goal either way.
                        tuple("401", AvailabilityStatus.OPERATIONAL),
                        tuple("402", AvailabilityStatus.OPERATIONAL),
                        // An id we have never seen falls back to the flag rather than to nothing.
                        tuple("403", AvailabilityStatus.OUT_OF_SERVICE));
    }

    @Test
    @DisplayName("drops sites that are planned, decommissioned or a duplicate listing")
    void dropsSitesThatAreNotRealInfrastructure() {
        // These would put chargers on the map that nobody can drive to, and a duplicate listing also
        // works against the geo-deduplication in the ingestion port.
        expectReferenceData();
        expectPoiPage(0, "[" + withStatus(500, 150) + "," + withStatus(501, 200) + ","
                + withStatus(502, 210) + "," + withStatus(503, 50) + "]");

        List<SourceStation> stations = adapter(properties(List.of("DE"), 500)).fetchStations().toList();

        server.verify();
        assertThat(stations).extracting(SourceStation::sourceStationId).containsExactly("503");
    }

    @Test
    @DisplayName("crawls in full when a country has no watermark yet")
    void firstRunIsAFullCrawl() {
        expectReferenceData();
        server.expect(queryParam("countrycode", "DE"))
                .andRespond(withSuccess("[" + poi(1, 25) + "]", MediaType.APPLICATION_JSON));

        adapter(properties(List.of("DE"), 500, true)).fetchStations().toList();

        server.verify();
    }

    @Test
    @DisplayName("asks only for what changed once a country has a watermark")
    void laterRunsAreIncremental() {
        Instant watermark = Instant.now().minus(Duration.ofDays(1));
        syncState.recordWatermark("OCM", "DE", watermark);
        expectReferenceData();
        // Re-asked from before the watermark: the overlap absorbs clock skew and uncommitted records.
        server.expect(queryParam("modifiedsince",
                        DateTimeFormatter.ISO_INSTANT.format(watermark.minus(Duration.ofDays(2)))))
                .andRespond(withSuccess("[" + poi(1, 25) + "]", MediaType.APPLICATION_JSON));

        adapter(properties(List.of("DE"), 500, true)).fetchStations().toList();

        server.verify();
    }

    @Test
    @DisplayName("falls back to a full crawl once the watermark is older than the refresh interval")
    void staleWatermarkForcesAFullCrawl() {
        // Incremental fetches never reveal upstream deletions, so drift has to be swept periodically.
        syncState.recordWatermark("OCM", "DE", Instant.now().minus(Duration.ofDays(8)));
        expectReferenceData();
        server.expect(queryParam("countrycode", "DE"))
                .andRespond(withSuccess("[" + poi(1, 25) + "]", MediaType.APPLICATION_JSON));

        adapter(properties(List.of("DE"), 500, true)).fetchStations().toList();

        server.verify();
    }

    @Test
    @DisplayName("advances the watermark only once the ingestion confirms the run committed")
    void watermarkAdvancesOnlyOnCommitProgress() {
        expectReferenceData();
        expectPoiPage(0, "[" + poi(1, 25) + "]");
        OpenChargeMapSourceAdapter adapter = adapter(properties(List.of("DE"), 500, true));

        adapter.fetchStations().toList();
        // Fetching is not storing: until the ingestion says it committed, nothing may be skipped next run.
        assertThat(syncState.watermark("OCM", "DE")).isEmpty();

        adapter.commitProgress();
        assertThat(syncState.watermark("OCM", "DE")).isPresent();
        server.verify();
    }

    @Test
    @DisplayName("earns no watermark from a crawl that was cut short")
    void truncatedCrawlEarnsNoWatermark() {
        // Every page is full, so the cap fires before the country is exhausted. Advancing here would
        // permanently skip everything past the cap.
        OpenChargeMapProperties cappedAtOnePage = new OpenChargeMapProperties(true, BASE_URL, "test-key",
                List.of("DE"), 1, Duration.ZERO, 1, true, Duration.ofSeconds(5), "evmap-tests",
                true, Duration.ofDays(2), Duration.ofDays(7));
        expectReferenceData();
        expectPoiPage(0, "[" + poi(1, 25) + "]");
        OpenChargeMapSourceAdapter adapter = adapter(cappedAtOnePage);

        adapter.fetchStations().toList();
        adapter.commitProgress();

        server.verify();
        assertThat(syncState.watermark("OCM", "DE")).isEmpty();
    }

    @Test
    @DisplayName("advances each country independently, so one failure does not hold the others back")
    void watermarksArePerCountry() {
        expectReferenceData();
        server.expect(queryParam("countrycode", "DE"))
                .andRespond(withSuccess("[" + poi(1, 25) + "]", MediaType.APPLICATION_JSON));
        server.expect(queryParam("countrycode", "NL"))
                .andRespond(withSuccess("[" + poi(2, 25) + "]", MediaType.APPLICATION_JSON));
        OpenChargeMapSourceAdapter adapter = adapter(properties(List.of("DE", "NL"), 500, true));

        adapter.fetchStations().toList();
        adapter.commitProgress();

        server.verify();
        assertThat(syncState.watermark("OCM", "DE")).isPresent();
        assertThat(syncState.watermark("OCM", "NL")).isPresent();
    }

    @Test
    @DisplayName("stops a country at the configured page cap")
    void honoursThePageCap() {
        OpenChargeMapProperties cappedAtTwoPages = new OpenChargeMapProperties(true, BASE_URL, "test-key",
                List.of("DE"), 1, Duration.ZERO, 2, true, Duration.ofSeconds(5), "evmap-tests",
                false, Duration.ofDays(2), Duration.ofDays(7));
        expectReferenceData();
        // Every page is full, so without the cap this would page forever.
        expectPoiPage(0, "[" + poi(1, 25) + "]");
        expectPoiPage(1, "[" + poi(2, 25) + "]");

        List<SourceStation> stations = adapter(cappedAtTwoPages).fetchStations().toList();

        server.verify();
        assertThat(stations).hasSize(2);
    }
}
