package de.joinside.evmap_service.availability.mobidata;

import de.joinside.evmap_service.availability.ChargePointAvailability;
import de.joinside.evmap_service.availability.GeoBounds;
import de.joinside.evmap_service.availability.LiveAvailability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Fixtures are trimmed copies of real OCPI 3.0 responses from
 * {@code api.mobidata-bw.de/ocpdb/api/ocpi/3.0/locations}, keeping the field shapes that matter:
 * the charge-station level between location and EVSE, {@code status_last_updated} being separate
 * from {@code last_updated}, and the mix of starred and compact EVSE-ID spellings.
 */
class MobiDataBwAvailabilityProviderTests {
    private static final String BASE_URL = "https://api.mobidata-bw.de/ocpdb/api/ocpi/3.0";
    private static final GeoBounds BOUNDS = new GeoBounds(48.77, 9.17, 48.78, 9.19);

    private MockRestServiceServer server;
    private MobiDataBwAvailabilityProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new MobiDataBwAvailabilityProvider(properties(), builder.build());
    }

    private static MobiDataProperties properties() {
        return new MobiDataProperties(true, BASE_URL, List.of("DE", "CH"), 200, 3, Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("reads live EVSE statuses out of the nested OCPI 3.0 payload")
    void readsLiveStatuses() {
        server.expect(queryParam("lat_min", "48.77"))
                .andExpect(queryParam("lon_max", "9.19"))
                .andRespond(withSuccess("""
                        {"items": [{"id": "316593", "source": "datex2_ecomovement", "charging_pool": [
                          {"evses": [
                            {"evse_id": "DE*AEW*E009903", "original_uid": "DE*AEW*E009903",
                             "status": "AVAILABLE",
                             "status_last_updated": "2026-08-24T02:01:16Z",
                             "last_updated": "2024-03-21T11:25:18Z"},
                            {"evse_id": "DEAEWE004502", "original_uid": "DEAEWE004502",
                             "status": "CHARGING",
                             "status_last_updated": "2026-08-24T01:55:00Z",
                             "last_updated": "2026-08-24T01:55:00Z"}
                          ]}
                        ]}], "total_count": 1}
                        """, MediaType.APPLICATION_JSON));

        assertThat(provider.fetch(BOUNDS))
                .extracting(ChargePointAvailability::evseId, ChargePointAvailability::status)
                // Both spellings arrive normalized, so the service compares them to stored ids directly.
                .containsExactly(tuple("DEAEWE009903", LiveAvailability.AVAILABLE),
                        tuple("DEAEWE004502", LiveAvailability.OCCUPIED));
    }

    @Test
    @DisplayName("timestamps a status from status_last_updated, not from the static record's age")
    void prefersTheStatusTimestamp() {
        // The distinction is not academic: this record's static description is from 2024 while its
        // status changed minutes ago. Using last_updated would present a fresh reading as two years
        // old, and the client would rightly hide it.
        server.expect(queryParam("lat_min", "48.77")).andRespond(withSuccess("""
                {"items": [{"id": "1", "charging_pool": [{"evses": [
                  {"evse_id": "DEAEWE009903", "status": "AVAILABLE",
                   "status_last_updated": "2026-08-24T02:01:16Z",
                   "last_updated": "2024-03-21T11:25:18Z"}
                ]}]}], "total_count": 1}
                """, MediaType.APPLICATION_JSON));

        assertThat(provider.fetch(BOUNDS)).singleElement()
                .extracting(ChargePointAvailability::observedAt)
                .isEqualTo(Instant.parse("2026-08-24T02:01:16Z"));
    }

    @Test
    @DisplayName("skips records that carry no live status")
    void skipsStaticRecords() {
        // 88 % of OCPDB's EVSEs are static register copies. Reporting them would put a live badge on
        // stations that have no dynamic feed behind them.
        server.expect(queryParam("lat_min", "48.77")).andRespond(withSuccess("""
                {"items": [{"id": "1", "source": "bnetza_api", "charging_pool": [{"evses": [
                  {"evse_id": "DELUEE001601", "status": "STATIC", "last_updated": "2026-08-18T13:28:10Z"},
                  {"evse_id": "DELUEE001602", "status": "PLANNED", "last_updated": "2026-08-18T13:28:10Z"}
                ]}]}], "total_count": 1}
                """, MediaType.APPLICATION_JSON));

        assertThat(provider.fetch(BOUNDS)).isEmpty();
    }

    @Test
    @DisplayName("skips OCPDB's synthesized BNetzA identifiers rather than joining on them")
    void skipsSyntheticIdentifiers() {
        // BNETZA*<Ladeeinrichtungs-ID>*<n> is OCPDB's own invention, not something an operator
        // publishes. Emitting it would add keys to the join that nothing in the register can match.
        server.expect(queryParam("lat_min", "48.77")).andRespond(withSuccess("""
                {"items": [{"id": "1", "charging_pool": [{"evses": [
                  {"evse_id": "BNETZA*1052034*1", "original_uid": "BNETZA*1052034*1",
                   "status": "AVAILABLE", "status_last_updated": "2026-08-24T02:01:16Z"}
                ]}]}], "total_count": 1}
                """, MediaType.APPLICATION_JSON));

        assertThat(provider.fetch(BOUNDS)).isEmpty();
    }

    @Test
    @DisplayName("falls back to original_uid when OCPDB rewrote the EVSE-ID")
    void fallsBackToTheOriginalIdentifier() {
        server.expect(queryParam("lat_min", "48.77")).andRespond(withSuccess("""
                {"items": [{"id": "1", "charging_pool": [{"evses": [
                  {"evse_id": "", "original_uid": "DE*EBW*E912316*1",
                   "status": "OUTOFORDER", "status_last_updated": "2026-08-24T02:01:16Z"}
                ]}]}], "total_count": 1}
                """, MediaType.APPLICATION_JSON));

        assertThat(provider.fetch(BOUNDS)).singleElement()
                .extracting(ChargePointAvailability::evseId, ChargePointAvailability::status)
                .containsExactly("DEEBWE9123161", LiveAvailability.OUT_OF_ORDER);
    }

    @Test
    @DisplayName("pages until the reported total is read")
    void pagesThroughResults() {
        server.expect(queryParam("offset", "0")).andRespond(withSuccess("""
                {"items": [{"id": "1", "charging_pool": [{"evses": [
                  {"evse_id": "DEAEWE000001", "status": "AVAILABLE", "status_last_updated": "2026-08-24T02:00:00Z"}
                ]}]}], "total_count": 2}
                """, MediaType.APPLICATION_JSON));
        server.expect(queryParam("offset", "1")).andRespond(withSuccess("""
                {"items": [{"id": "2", "charging_pool": [{"evses": [
                  {"evse_id": "DEAEWE000002", "status": "CHARGING", "status_last_updated": "2026-08-24T02:00:00Z"}
                ]}]}], "total_count": 2}
                """, MediaType.APPLICATION_JSON));

        assertThat(provider.fetch(BOUNDS)).extracting(ChargePointAvailability::evseId)
                .containsExactly("DEAEWE000001", "DEAEWE000002");
        server.verify();
    }

    @Test
    @DisplayName("answers empty when the access point is down, instead of failing the request")
    void degradesWhenTheSourceIsDown() {
        // A national access point is allowed to be unreachable. The station detail and the map around
        // it must keep working; that area simply answers UNKNOWN.
        server.expect(queryParam("lat_min", "48.77")).andRespond(withServerError());

        assertThat(provider.fetch(BOUNDS)).isEmpty();
    }

    @Test
    @DisplayName("claims only the countries it is configured for")
    void claimsConfiguredCountries() {
        assertThat(provider.covers("DE")).isTrue();
        assertThat(provider.covers("CH")).isTrue();
        // France has its own national access point; letting a regional German source claim it would
        // mask the gap rather than fill it.
        assertThat(provider.covers("FR")).isFalse();
    }
}
