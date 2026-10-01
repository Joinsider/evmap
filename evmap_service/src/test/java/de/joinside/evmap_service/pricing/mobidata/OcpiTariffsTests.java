package de.joinside.evmap_service.pricing.mobidata;

import de.joinside.evmap_service.pricing.AdHocPrice;
import de.joinside.evmap_service.pricing.mobidata.OcpiTariffs.Element;
import de.joinside.evmap_service.pricing.mobidata.OcpiTariffs.PriceComponent;
import de.joinside.evmap_service.pricing.mobidata.OcpiTariffs.Tariff;
import de.joinside.evmap_service.pricing.mobidata.OcpiTariffs.Tax;
import de.joinside.evmap_service.pricing.mobidata.OcpiTariffs.TimeUnit;
import de.joinside.evmap_service.pricing.mobidata.OcpiTariffs.VatBasisTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tariffs are shaped like OCPDB's {@code /tariffs} on 2026-10-01 (release 2.16.2): net prices with a VAT
 * percentage as a string, the DATEX {@code pricePerMinute} unconverted in {@code TIME}, chargecloud without any
 * VAT, and {@code max_duration: 0} for "no end".
 */
class OcpiTariffsTests {
    private static final Instant UPDATED = Instant.parse("2026-09-30T21:58:47Z");
    private static final VatBasisTable NO_TABLE = VatBasisTable.of(List.of(), List.of());
    private static final VatBasisTable ALLEGO_NET = VatBasisTable.of(List.of("Allego"), List.of());

    private static PriceComponent component(String type, String price, String vat) {
        return new PriceComponent(type, new BigDecimal(price), vat == null ? null : List.of(new Tax("VAT", vat)), null);
    }

    private static Element element(PriceComponent component) {
        return new Element(List.of(component), null);
    }

    private static Element after(int seconds, PriceComponent component) {
        return new Element(List.of(component), Map.of("min_duration", seconds, "max_duration", 0));
    }

    private static Tariff tariff(String source, Element... elements) {
        return new Tariff("t1", source, "EUR", UPDATED, null, List.of(elements));
    }

    private static AdHocPrice read(Tariff tariff, String operator, VatBasisTable table) {
        return OcpiTariffs.read(tariff, operator, TimeUnit.PER_MINUTE, table);
    }

    @Test
    @DisplayName("a net price proven by the arithmetic is shown gross (Lidl 0,4622 + 19 % = 0,55)")
    void provenNetPrice() {
        AdHocPrice price = read(tariff("datex2_ecomovement", element(component("ENERGY", "0.4622", "19"))), "Lidl", NO_TABLE);

        assertThat(price.energyPerKwh()).isEqualByComparingTo("0.55");
        assertThat(price.currency()).isEqualTo("EUR");
        assertThat(price.observedAt()).isEqualTo(UPDATED);
        assertThat(price.free()).isFalse();
    }

    @Test
    @DisplayName("EnBW: net energy price and a per-minute fee from minute 30, both made gross")
    void enbwWithTimeFee() {
        AdHocPrice price = read(tariff("datex2_enbw",
                element(component("ENERGY", "0.66386555", "19")),
                after(1800, component("TIME", "0.20168067", "19"))), "ENBW", NO_TABLE);

        assertThat(price.energyPerKwh()).isEqualByComparingTo("0.79");
        assertThat(price.timeFees()).singleElement().satisfies(fee -> {
            assertThat(fee.fromMinute()).isEqualTo(30);
            assertThat(fee.perMinute()).isEqualByComparingTo("0.24");
        });
    }

    @Test
    @DisplayName("a round value is shown only for an operator the table names (Allego 0,64 net = 0,76)")
    void roundValueNeedsTheTable() {
        Tariff allego = tariff("datex2_ecomovement", element(component("ENERGY", "0.64", "19")));

        assertThat(read(allego, "Allego", NO_TABLE)).isNull();
        assertThat(read(allego, "allego ", ALLEGO_NET).energyPerKwh()).isEqualByComparingTo("0.76");
        assertThat(read(allego, "Aral pulse", ALLEGO_NET)).isNull();
    }

    @Test
    @DisplayName("without any VAT (chargecloud) only a gross operator from the table gets a price")
    void withoutVat() {
        Tariff chargecloud = tariff("datex2_chargecloud",
                element(component("FLAT", "1.5", null)),
                element(component("ENERGY", "0.46", null)),
                element(component("TIME", "0.0", null)),
                after(14400, component("TIME", "0.06", null)),
                after(14400, component("TIME", "0.06", null)));

        assertThat(read(chargecloud, "TankE GmbH", NO_TABLE)).isNull();
        assertThat(read(chargecloud, "TankE GmbH", VatBasisTable.of(List.of("TankE GmbH"), List.of()))).isNull();
        AdHocPrice gross = read(chargecloud, "TankE GmbH", VatBasisTable.of(List.of(), List.of("TankE GmbH")));
        assertThat(gross.energyPerKwh()).isEqualByComparingTo("0.46");
        assertThat(gross.sessionFee()).isEqualByComparingTo("1.5");
        assertThat(gross.timeFees()).singleElement().satisfies(fee -> {
            assertThat(fee.fromMinute()).isEqualTo(240);
            assertThat(fee.perMinute()).isEqualByComparingTo("0.06");
        });
    }

    @Test
    @DisplayName("an explicit tax_included wins over evidence and table")
    void explicitFlagWins() {
        Tariff gross = new Tariff("t", "datex2_ecomovement", "EUR", UPDATED, true,
                List.of(element(component("ENERGY", "0.4622", "19"))));
        Tariff net = new Tariff("t", "datex2_ecomovement", "EUR", UPDATED, false,
                List.of(element(component("ENERGY", "0.64", "19"))));

        assertThat(read(gross, "Lidl", NO_TABLE).energyPerKwh()).isEqualByComparingTo("0.4622");
        assertThat(read(net, "Allego", NO_TABLE).energyPerKwh()).isEqualByComparingTo("0.76");
    }

    @Test
    @DisplayName("a VAT written as a fraction reads like the percentage")
    void fractionalVat() {
        AdHocPrice price = read(tariff("datex2_ecomovement", element(component("ENERGY", "0.6639",
                "0.190000000000000002220446049250313080847263336181640625"))), "TotalEnergies", NO_TABLE);

        assertThat(price.energyPerKwh()).isEqualByComparingTo("0.79");
    }

    @Test
    @DisplayName("anything not fully understood gives no price")
    void uncertainTariffs() {
        PriceComponent energy = component("ENERGY", "0.4622", "19");
        // a time window
        assertThat(read(new Tariff("t", "s", "EUR", UPDATED, null, List.of(new Element(List.of(energy),
                Map.of("start_time", "08:00", "end_time", "20:00")))), "Lidl", NO_TABLE)).isNull();
        // two energy prices
        assertThat(read(tariff("s", element(energy), element(component("ENERGY", "0.5798", "19"))), "Lidl", NO_TABLE)).isNull();
        // an energy price that changes after a while
        assertThat(read(tariff("s", element(energy), after(3600, component("ENERGY", "0.4034", "19"))), "Lidl", NO_TABLE)).isNull();
        // two different fees from the same minute
        assertThat(read(tariff("s", element(energy), after(60, component("TIME", "0.1", "19")),
                after(60, component("TIME", "0.2", "19"))), "Lidl", NO_TABLE)).isNull();
        // no energy price at all (E.ON's time-only tariffs)
        assertThat(read(tariff("s", element(component("TIME", "0.1", "19"))), "E.ON Drive", NO_TABLE)).isNull();
        // another currency
        assertThat(read(new Tariff("t", "s", "CHF", UPDATED, null, List.of(element(energy))), "Lidl", NO_TABLE)).isNull();
        // outside the plausible band
        assertThat(read(tariff("s", element(component("ENERGY", "1.6807", "19"))), "x", NO_TABLE)).isNull();
    }

    @Test
    void anEnergyPriceOfZeroIsFree() {
        AdHocPrice price = read(tariff("s", element(component("ENERGY", "0", "19"))), "x", NO_TABLE);

        assertThat(price.free()).isTrue();
        assertThat(price.energyPerKwh()).isNull();
    }

    @Test
    @DisplayName("a parking fee is not shown but flagged as a further fee")
    void parkingFee() {
        AdHocPrice price = read(tariff("s", element(component("ENERGY", "0.4622", "19")),
                after(7200, component("PARKING_TIME", "0.1", "19"))), "Lidl", NO_TABLE);

        assertThat(price.timeFees()).isEmpty();
        assertThat(price.furtherFees()).isTrue();
    }

    @Test
    @DisplayName("the time unit decides the amount: per hour is divided, unknown has none")
    void timeUnits() {
        Tariff tariff = tariff("s", element(component("ENERGY", "0.4622", "19")),
                after(3600, component("TIME", "6", "19")));

        assertThat(OcpiTariffs.read(tariff, "x", TimeUnit.PER_HOUR, NO_TABLE).timeFees())
                .singleElement().satisfies(fee -> assertThat(fee.perMinute()).isEqualByComparingTo("0.119"));
        assertThat(OcpiTariffs.read(tariff, "x", TimeUnit.UNKNOWN, NO_TABLE).timeFees())
                .singleElement().satisfies(fee -> assertThat(fee.perMinute()).isNull());
        // 6 € read as per minute is outside the band: the fee stays, its amount goes.
        assertThat(OcpiTariffs.read(tariff, "x", TimeUnit.PER_MINUTE, NO_TABLE).timeFees())
                .singleElement().satisfies(fee -> assertThat(fee.perMinute()).isNull());
    }

    @Test
    @DisplayName("detects the time unit per feed from the median of its time prices")
    void detectsTimeUnitsPerFeed() {
        List<Tariff> tariffs = new ArrayList<>();
        for (String price : List.of("0.06", "0.1", "0.2", "0.0333", "3.0"))
            tariffs.add(tariff("datex2_ecomovement", after(3600, component("TIME", price, "19"))));
        for (String price : List.of("3.6", "6", "12"))
            tariffs.add(tariff("fixed_upstream", after(3600, component("TIME", price, "19"))));
        tariffs.add(tariff("no_time", element(component("ENERGY", "0.5", "19"))));
        tariffs.add(tariff("absurd", after(60, component("TIME", "500", "19"))));

        assertThat(OcpiTariffs.detectTimeUnits(tariffs)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "datex2_ecomovement", TimeUnit.PER_MINUTE,
                "fixed_upstream", TimeUnit.PER_HOUR,
                "absurd", TimeUnit.UNKNOWN));
    }

    @ParameterizedTest(name = "{0} net at {1} → {2}")
    @CsvSource({"0.4622,0.19,0.55", "0.4034,0.19,0.48", "0.6639,0.19,0.79", "0.4874,0.19,0.58", "0.504,0.19,0.60"})
    void provesNetPrices(String net, String rate, String gross) {
        assertThat(OcpiTariffs.provenGross(new BigDecimal(net), new BigDecimal(rate))).isEqualByComparingTo(gross);
    }

    @ParameterizedTest(name = "{0} is not proven net")
    @CsvSource({"0.64", "0.55", "0.6018", "0.805", "0.411"})
    void doesNotProveRoundOrUnexplainedValues(String net) {
        assertThat(OcpiTariffs.provenGross(new BigDecimal(net), new BigDecimal("0.19"))).isNull();
    }
}
