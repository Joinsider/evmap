package de.joinside.evmap_service.pricing;

import de.joinside.evmap_service.availability.Attribution;
import de.joinside.evmap_service.availability.GeoBounds;
import de.joinside.evmap_service.support.PostgisDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The read side against a real PostGIS: register prices from {@code master.charge_point_price}, live tariffs
 * joined by exact EVSE-ID, the live one winning, and the operator per charge point falling back to the station's.
 */
class PricingServiceTests {
    private static final Instant OBSERVED = Instant.parse("2026-09-30T00:00:00Z");
    private static final AdHocPrice LIVE = new AdHocPrice("EUR", new BigDecimal("0.55"), null,
            List.of(new AdHocPrice.TimeFee(240, new BigDecimal("0.1"))), false, false, OBSERVED);

    private ChargePointInventory inventory;
    private UUID station;
    private UUID livePoint;
    private UUID registerPoint;
    private UUID unpricedPoint;

    private static final class GermanTariffs implements PriceProvider {
        private int calls;
        private boolean fail;

        @Override
        public String source() {
            return "German";
        }

        @Override
        public Attribution attribution() {
            return new Attribution("MobiData BW", "dl-de/by-2.0", "https://www.mobidata-bw.de");
        }

        @Override
        public boolean covers(String countryCode) {
            return "DE".equals(countryCode);
        }

        @Override
        public List<ChargePointPrice> fetch(GeoBounds bounds) {
            calls++;
            if (fail) throw new IllegalStateException("upstream broke");
            return List.of(new ChargePointPrice("DELIDE1", LIVE), new ChargePointPrice("DEUNKNOWN9", LIVE));
        }
    }

    @BeforeEach
    void setUp() {
        PostgisDatabase.clearMasterData();
        inventory = new ChargePointInventory(PostgisDatabase.jdbc());
        station = PostgisDatabase.insertStation("Lidl Stuttgart", "Lidl", "DE", 48.7758, 9.1829);
        livePoint = PostgisDatabase.insertChargePoint(station, "1*1", "DE*LID*E1");
        registerPoint = PostgisDatabase.insertChargePoint(station, "1*2", "DE*LID*E2");
        unpricedPoint = PostgisDatabase.insertChargePoint(station, "1*3", null);

        PostgisDatabase.jdbc().sql("UPDATE master.charge_point SET operator_name='Partner AG' WHERE id=:id")
                .param("id", unpricedPoint).update();
        PostgisDatabase.jdbc().sql("INSERT INTO master.charge_point_price (charge_point_id, currency, energy_per_kwh, "
                        + "time_fees, free, further_fees, observed_at) VALUES (:id, 'EUR', 0.3710, "
                        + "'[{\"fromMinute\": 0, \"perMinute\": 0.0250}]'::jsonb, false, true, :at)")
                .param("id", registerPoint).param("at", java.sql.Timestamp.from(OBSERVED)).update();
        // The register would be overridden by a live tariff for the same charge point.
        PostgisDatabase.jdbc().sql("INSERT INTO master.charge_point_price (charge_point_id, currency, energy_per_kwh, "
                        + "free, further_fees) VALUES (:id, 'EUR', 0.99, false, false)")
                .param("id", livePoint).update();
        PostgisDatabase.jdbc().sql("INSERT INTO master.charging_connector (id, station_id, charge_point_id, connector_type, "
                        + "power_kw, quantity) VALUES (:id, :station, :cp, 'CCS', 150, 1)")
                .param("id", UUID.randomUUID()).param("station", station).param("cp", livePoint).update();
    }

    private PricingService service(PriceProvider provider) {
        return new PricingService(List.of(provider), inventory,
                new PricingProperties(true, Duration.ofMinutes(15), 500, 300));
    }

    @Test
    @DisplayName("merges live tariffs and register prices per charge point, the live one first")
    void mergesPrices() {
        StationPrices prices = service(new GermanTariffs()).forStation(station).orElseThrow();

        assertThat(prices.chargePoints()).hasSize(3);
        StationPrices.PricedChargePoint live = byId(prices, livePoint);
        assertThat(live.price()).isEqualTo(LIVE);
        assertThat(live.priceSource()).isEqualTo("MobiData BW");
        assertThat(live.connectors()).singleElement().satisfies(c -> assertThat(c.connectorType()).isEqualTo("CCS"));

        StationPrices.PricedChargePoint register = byId(prices, registerPoint);
        assertThat(register.priceSource()).isEqualTo("TEST");
        assertThat(register.price().energyPerKwh()).isEqualByComparingTo("0.371");
        assertThat(register.price().energyPerKwh().toPlainString()).isEqualTo("0.371");
        assertThat(register.price().timeFees()).singleElement()
                .satisfies(fee -> assertThat(fee.perMinute()).isEqualByComparingTo("0.025"));
        assertThat(register.price().furtherFees()).isTrue();
        assertThat(register.price().observedAt()).isEqualTo(OBSERVED);

        assertThat(byId(prices, unpricedPoint).price()).isNull();
        assertThat(prices.cheapestEnergyPerKwh()).isEqualByComparingTo("0.371");
        assertThat(prices.currency()).isEqualTo("EUR");
        assertThat(prices.sources()).extracting(Attribution::name).containsExactly("MobiData BW");
    }

    @Test
    @DisplayName("the operator of a charge point falls back to the station's")
    void operatorPerChargePoint() {
        StationPrices prices = service(new GermanTariffs()).forStation(station).orElseThrow();

        assertThat(byId(prices, unpricedPoint).operatorName()).isEqualTo("Partner AG");
        assertThat(byId(prices, livePoint).operatorName()).isEqualTo("Lidl");
    }

    @Test
    @DisplayName("a failing provider leaves the register prices and the screen intact")
    void containsProviderFailures() {
        GermanTariffs provider = new GermanTariffs();
        provider.fail = true;

        StationPrices prices = service(provider).forStation(station).orElseThrow();

        assertThat(byId(prices, livePoint).price().energyPerKwh()).isEqualByComparingTo("0.99");
        assertThat(prices.sources()).isEmpty();
    }

    @Test
    @DisplayName("answers are cached per area, and a country the provider does not cover is not asked")
    void cachesAndScopes() {
        GermanTariffs provider = new GermanTariffs();
        PricingService service = service(provider);
        service.forStation(station);
        service.forStation(station);
        assertThat(provider.calls).isOne();

        UUID french = PostgisDatabase.insertStation("Paris", "Izivia", "FR", 48.8566, 2.3522);
        PostgisDatabase.insertChargePoint(french, "FR*1", "FR*IZI*E1");
        service.forStation(french);
        assertThat(provider.calls).isOne();
    }

    @Test
    void anUnknownStationIsEmpty() {
        assertThat(service(new GermanTariffs()).forStation(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("a stored price that states its VAT basis wins over the live tariff, which is then not even asked for")
    void statedBasisWins() {
        PostgisDatabase.jdbc().sql("DELETE FROM master.charge_point_price WHERE charge_point_id=:id")
                .param("id", livePoint).update();
        PostgisDatabase.jdbc().sql("UPDATE master.charge_point_price SET vat_basis_stated=true WHERE charge_point_id=:id")
                .param("id", registerPoint).update();
        insertStated(livePoint, 0, "0.7900", "{qrCode}", "[{\"fromMinute\": 240, \"toMinute\": 390, "
                + "\"perMinute\": 0.10, \"cap\": 12.00, \"window\": {\"from\": \"08:00\", \"to\": \"20:00\", "
                + "\"days\": [\"monday\", \"friday\"]}}]", null);
        insertStated(livePoint, 1, "0.8400", "{emv}", null,
                "[{\"perKwh\": 0.84, \"window\": {\"from\": \"08:00\", \"to\": \"22:00\"}}, "
                        + "{\"perKwh\": 0.69, \"window\": {\"from\": \"22:00\", \"to\": \"08:00\"}}]");
        GermanTariffs provider = new GermanTariffs();

        StationPrices prices = service(provider).forStation(station).orElseThrow();

        StationPrices.PricedChargePoint stated = byId(prices, livePoint);
        assertThat(stated.prices()).hasSize(2);
        assertThat(stated.price()).isNull();
        assertThat(stated.priceSource()).isEqualTo("Lidl via Mobilithek");
        AdHocPrice qr = stated.prices().getFirst();
        assertThat(qr.paymentMeans()).containsExactly("qrCode");
        assertThat(qr.timeFees()).singleElement().satisfies(fee -> {
            assertThat(fee.fromMinute()).isEqualTo(240);
            assertThat(fee.toMinute()).isEqualTo(390);
            assertThat(fee.cap()).isEqualByComparingTo("12");
            assertThat(fee.cap().toPlainString()).isEqualTo("12");
            assertThat(fee.window()).isEqualTo(new AdHocPrice.TimeWindow("08:00", "20:00", List.of("monday", "friday")));
        });
        assertThat(qr.hasLimits()).isTrue();
        AdHocPrice card = stated.prices().get(1);
        assertThat(card.energyPerKwh()).isNull();
        assertThat(card.energyWindows()).extracting(AdHocPrice.EnergyWindow::perKwh)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("0.84"), new BigDecimal("0.69"));
        assertThat(card.lowestEnergyPerKwh()).isEqualByComparingTo("0.69");
        // The cheapest of the station is the register's 0,371, the card's lowest its night window; no live source.
        assertThat(prices.cheapestEnergyPerKwh()).isEqualByComparingTo("0.371");
        assertThat(prices.sources()).isEmpty();
        assertThat(provider.calls).isZero();
    }

    @Test
    @DisplayName("a stored price without a stated VAT basis stays behind the live tariff")
    void inferredBasisStaysBehind() {
        PostgisDatabase.jdbc().sql("DELETE FROM master.charge_point_price WHERE charge_point_id=:id")
                .param("id", livePoint).update();
        PostgisDatabase.jdbc().sql("INSERT INTO master.charge_point_price (charge_point_id, ordinal, currency, "
                        + "energy_per_kwh, vat_basis_stated, stated_by) VALUES (:id, 0, 'EUR', 0.49, false, 'chargecloud')")
                .param("id", livePoint).update();

        StationPrices prices = service(new GermanTariffs()).forStation(station).orElseThrow();

        assertThat(byId(prices, livePoint).price()).isEqualTo(LIVE);
        assertThat(byId(prices, livePoint).priceSource()).isEqualTo("MobiData BW");
    }

    @Test
    @DisplayName("a stored detail that does not read costs only that detail")
    void unreadableDetail() {
        PostgisDatabase.jdbc().sql("UPDATE master.charge_point_price SET time_fees='{\"not\": \"a list\"}'::jsonb "
                + "WHERE charge_point_id=:id").param("id", registerPoint).update();

        StationPrices prices = service(new GermanTariffs()).forStation(station).orElseThrow();

        assertThat(byId(prices, registerPoint).price().energyPerKwh()).isEqualByComparingTo("0.371");
        assertThat(byId(prices, registerPoint).price().timeFees()).isEmpty();
    }

    private static void insertStated(UUID chargePoint, int ordinal, String energy, String means, String timeFees,
                                     String energyWindows) {
        PostgisDatabase.jdbc().sql("INSERT INTO master.charge_point_price (charge_point_id, ordinal, currency, "
                        + "energy_per_kwh, energy_windows, time_fees, payment_means, vat_basis_stated, stated_by) "
                        + "VALUES (:id, :ordinal, 'EUR', CAST(:energy AS numeric), CAST(:windows AS jsonb), "
                        + "CAST(:fees AS jsonb), CAST(:means AS varchar[]), true, 'Lidl via Mobilithek')")
                .param("id", chargePoint).param("ordinal", ordinal)
                .param("energy", energyWindows == null ? energy : null)
                .param("windows", energyWindows).param("fees", timeFees).param("means", means).update();
    }

    private static StationPrices.PricedChargePoint byId(StationPrices prices, UUID id) {
        return prices.chargePoints().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }
}
