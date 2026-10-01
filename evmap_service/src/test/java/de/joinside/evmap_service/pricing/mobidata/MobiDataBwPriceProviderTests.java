package de.joinside.evmap_service.pricing.mobidata;

import de.joinside.evmap_service.availability.GeoBounds;
import de.joinside.evmap_service.pricing.ChargePointPrice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Fixtures are trimmed copies of OCPDB's {@code /tariffs} and {@code /locations} (OCPI 3.0) from 2026-10-01: a
 * Lidl tariff whose net price proves itself, an Allego tariff that needs the operator table, a chargecloud
 * tariff without VAT, an EVSE whose plugs name two tariffs, and OCPDB's synthetic BNetzA id.
 */
class MobiDataBwPriceProviderTests {
    private static final String BASE_URL = "https://api.mobidata-bw.de/ocpdb/api/ocpi/3.0";
    private static final GeoBounds BOUNDS = new GeoBounds(48.77, 9.17, 48.78, 9.19);

    private static final String TARIFFS = """
            {"items": [
              {"source": "datex2_ecomovement", "currency": "EUR", "last_updated": "2026-09-01T11:48:38Z", "id": "1",
               "original_id": "lidl-dc", "elements": [
                 {"price_components": [{"type": "ENERGY", "price": 0.4622, "taxes": [{"name": "VAT", "percentage": "19"}]}]},
                 {"price_components": [{"type": "TIME", "price": 0.1, "taxes": [{"name": "VAT", "percentage": "19"}]}],
                  "restrictions": {"min_duration": 3600, "max_duration": 0}}]},
              {"source": "datex2_ecomovement", "currency": "EUR", "last_updated": "2026-09-01T11:48:38Z", "id": "2",
               "original_id": "allego-dc", "elements": [
                 {"price_components": [{"type": "ENERGY", "price": 0.64, "taxes": [{"name": "VAT", "percentage": "19"}]}]}]},
              {"source": "datex2_chargecloud", "currency": "EUR", "last_updated": "2026-09-30T13:16:12Z", "id": "3",
               "original_id": "tanke-ac", "elements": [
                 {"price_components": [{"type": "ENERGY", "price": 0.49}]}]}
            ], "total_count": 3}
            """;

    private static final String LOCATIONS = """
            {"items": [
              {"id": "1", "operator": {"name": "Lidl"}, "charging_pool": [{"evses": [
                {"evse_id": "DE*LID*E1*1", "connectors": [{"standard": "IEC_62196_T2_COMBO", "tariff_ids": ["lidl-dc"]}]},
                {"evse_id": "DE*LID*E1*2", "connectors": [{"tariff_ids": ["lidl-dc"]}, {"tariff_ids": ["allego-dc"]}]},
                {"evse_id": null, "original_uid": "BNETZA*123*1", "connectors": [{"tariff_ids": ["lidl-dc"]}]}]}]},
              {"id": "2", "operator": {"name": "Allego"}, "charging_pool": [{"evses": [
                {"evse_id": "DE*ALG*E7", "connectors": [{"tariff_ids": ["allego-dc"]}]}]}]},
              {"id": "3", "operator": {"name": "TankE GmbH"}, "charging_pool": [{"evses": [
                {"evse_id": "DE*TNK*E1", "connectors": [{"tariff_ids": ["tanke-ac"]}]}]}]}
            ], "total_count": 3}
            """;

    private MockRestServiceServer server;
    private MobiDataBwPriceProvider provider;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T12:00:00Z"));

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new MobiDataBwPriceProvider(new MobiDataPricingProperties(true, BASE_URL, List.of("DE"), 200, 3,
                1000, 10, Duration.ofMinutes(15), Duration.ofSeconds(10), List.of("Allego"), List.of()),
                builder.build(), clock);
    }

    private void expectTariffs() {
        server.expect(once(), requestTo(BASE_URL + "/tariffs?limit=1000&offset=0"))
                .andRespond(withSuccess(TARIFFS, MediaType.APPLICATION_JSON));
    }

    private void expectLocations() {
        server.expect(once(), request -> assertThat(request.getURI().getPath()).endsWith("/locations"))
                .andRespond(withSuccess(LOCATIONS, MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("prices EVSEs whose single tariff reads, and skips the rest")
    void pricesCertainTariffs() {
        expectTariffs();
        expectLocations();

        List<ChargePointPrice> prices = provider.fetch(BOUNDS);

        assertThat(prices).extracting(ChargePointPrice::evseId).containsExactlyInAnyOrder("DELIDE11", "DEALGE7");
        assertThat(prices).filteredOn(p -> p.evseId().equals("DELIDE11")).singleElement().satisfies(p -> {
            assertThat(p.price().energyPerKwh()).isEqualByComparingTo("0.55");
            // Per minute, as the feed's median says, made gross.
            assertThat(p.price().timeFees()).singleElement().satisfies(fee -> {
                assertThat(fee.fromMinute()).isEqualTo(60);
                assertThat(fee.perMinute()).isEqualByComparingTo("0.119");
            });
        });
        assertThat(prices).filteredOn(p -> p.evseId().equals("DEALGE7")).singleElement()
                .satisfies(p -> assertThat(p.price().energyPerKwh()).isEqualByComparingTo("0.76"));
        server.verify();
    }

    @Test
    @DisplayName("reuses the tariff list until it expires, and keeps it when a refresh fails")
    void reusesAndKeepsTheTariffList() {
        expectTariffs();
        expectLocations();
        expectLocations();
        provider.fetch(BOUNDS);
        provider.fetch(BOUNDS);
        server.verify();

        server.reset();
        clock.advance(Duration.ofMinutes(16));
        server.expect(once(), requestTo(BASE_URL + "/tariffs?limit=1000&offset=0")).andRespond(withServerError());
        expectLocations();
        assertThat(provider.fetch(BOUNDS)).hasSize(2);
        server.verify();
    }

    @Test
    @DisplayName("an unreachable source yields no prices rather than an error")
    void degradesQuietly() {
        server.expect(once(), requestTo(BASE_URL + "/tariffs?limit=1000&offset=0")).andRespond(withServerError());

        assertThat(provider.fetch(BOUNDS)).isEmpty();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
