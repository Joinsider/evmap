package de.joinside.evmap_service.sync.irve;

import de.joinside.evmap_service.sync.SourceStation.SourcePrice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every text here is taken from the 2026-10-01 consolidation, with its number of charge points where it
 * matters. The rule under test is ADR 0022's: accept only what is understood completely, else no price.
 */
class IrvePriceTextTests {
    private static final Instant WHEN = Instant.parse("2026-09-01T00:00:00Z");

    private static SourcePrice parse(String text) {
        return IrvePriceText.parse(text, false, WHEN);
    }

    @ParameterizedTest(name = "{0} → {1} €/kWh")
    @CsvSource(delimiter = '|', value = {
            "0,29€ / kWh|0.29",                         // Lidl, 3.569
            "59 cts/kWh|0.59",                          // Allego, 678
            "0,54 € TTC / kWh|0.54",                    // SOWATT, 398
            "0.40€ / kwh|0.4",
            "0,40€ / kwh pour les non abonnés.|0.4",    // CAR2PLUG
            "0,50 € / kWh pour les non-abonnés|0.5",    // PartagemaBorne
            "0,66 € TTC par kWh|0.66",             // Z-E-N, non-breaking space
            "0,75 e/ kwh|0.75",                         // e-motum, "e" for euro
            "0,55 â‚¬/ kwh|0.55",                       // UTF-8 read as cp1252
            "0.45€TTC/kWh|0.45",
            "0,42€/KWHTTC|0.42",
            "AC 36cts/KWh|0.36",                        // Allego, type label
            "HPC 49cts/Kwh|0.49",
            "52 c€ / kWh|0.52"
    })
    void readsAPlainEnergyPriceAsGross(String text, String expected) {
        SourcePrice price = parse(text);

        assertThat(price).isNotNull();
        assertThat(price.energyPerKwh()).isEqualByComparingTo(expected);
        assertThat(price.currency()).isEqualTo("EUR");
        assertThat(price.free()).isFalse();
        assertThat(price.furtherFees()).isFalse();
        assertThat(price.observedAt()).isEqualTo(WHEN);
    }

    @Test
    void readsAStartFeeAndATimeFeeNextToTheEnergyPrice() {
        assertThat(parse("Bornes rapides: 2€ + 0.59€ / kWh"))
                .extracting(SourcePrice::sessionFee, SourcePrice::energyPerKwh)
                .satisfies(values -> {
                    assertThat((BigDecimal) values.get(0)).isEqualByComparingTo("2");
                    assertThat((BigDecimal) values.get(1)).isEqualByComparingTo("0.59");
                });
        SourcePrice timed = parse("0,17€/kWh + 0,125€/mn");
        assertThat(timed.energyPerKwh()).isEqualByComparingTo("0.17");
        assertThat(timed.timeFeePerMinute()).isEqualByComparingTo("0.125");
        SourcePrice session = parse("0,39E/kWh et 1E de cout fixe par session de recharge.");
        assertThat(session.energyPerKwh()).isEqualByComparingTo("0.39");
        assertThat(session.sessionFee()).isEqualByComparingTo("1");
    }

    @Test
    void readsATimeOnlyPrice() {
        SourcePrice price = parse("Charge normale : 0,025€ / min");   // Freshmile, 511

        assertThat(price.energyPerKwh()).isNull();
        assertThat(price.timeFeePerMinute()).isEqualByComparingTo("0.025");
    }

    @Test
    @DisplayName("explicit HT is converted at the French standard rate")
    void convertsExplicitNetPrices() {
        assertThat(parse("0,37 € HT / kWh").energyPerKwh()).isEqualByComparingTo("0.444");
        assertThat(parse("1,25€/KWH HT").energyPerKwh()).isEqualByComparingTo("1.5");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Les tarifs de recharge peuvent varier en fonction de plusieurs facteurs",
            "Inconnu", "Payant", "-", "TRUE", "https://belib.paris", "Grille tarifaire en ligne",
            "0.4583",                                   // no unit
            "0,22",
            "0,35cts/KWh",                              // 0,35 ct or 35 ct?
            "0.32€ / kW",                               // kW is not kWh
            "0.4083€/kWh",                              // four decimals, no VAT basis
            "2.5€ à la connexion et 2€/Kwh",            // outside the plausible band
            "0,00 € / kWh pour les non-abonnés",
            "0,35€/kWh + 0,03€/min entre 6h et 18h, 15€ la session entre 18h et 6h",   // time windows
            "0.392€/kWh jusqu'à 3h de charge, 0.392€/kWh et 1.66€/h après 3h de charge",
            "Tarification au kWh plus frais de connexion éventuelles en fonction de l abonnement détenu",
            "0,55€KWH HT",                              // no "/" between amount and unit
            "0.79 €/minute",                            // 47 € an hour: not a per-minute fee
            "0,45 € TTC / kWh + 0,10 € HT / min"        // two VAT bases
    })
    void answersNothingForAnythingNotUnderstoodCompletely(String text) {
        assertThat(parse(text)).isNull();
    }

    @Test
    void readsTheGeneratedFormatOnlyWhereTheArithmeticProvesItNet() {
        // EASYCHARGE, 3.153 charge points: 0,30916667 × 1,2 = 0,371
        SourcePrice easycharge = parse("entre 08:00 et 20:00 : 0.30916667€ par kwh de charge, 3.75€ par heure "
                + "d'occupation hors charge, entre 20:00 et 08:00 : 0.30916667€ par kwh de charge, "
                + "par défaut : 0.30916667€ par kwh de charge");
        assertThat(easycharge.energyPerKwh()).isEqualByComparingTo("0.371");
        assertThat(easycharge.furtherFees()).isTrue();

        // Citeos in mojibake: 0,4167 × 1,2 = 0,50
        SourcePrice citeos = parse("par dķfaut : 0.4167Ć par kwh de charge, par dķfaut : 6.0Ć par heure "
                + "d'occupation hors charge, 0.4167Ć par kwh de charge");
        assertThat(citeos.energyPerKwh()).isEqualByComparingTo("0.5");

        SourcePrice noFees = parse("par défaut : 0.3333€ par kwh de charge");
        assertThat(noFees.energyPerKwh()).isEqualByComparingTo("0.4");
        assertThat(noFees.furtherFees()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "par défaut : 6.0€ par heure d'occupation hors charge, 0.42€ par kwh de charge",   // round: either
            "par défaut : 0.33334€ par kwh de charge",                                          // no evidence
            "par défaut : 0.42€ par kwh de charge, 6.0€ par heure d'occupation hors charge, entre 08:00 et 20:00 "
                    + ": 0.4525€ par kwh de charge"                                              // two prices
    })
    void rejectsTheGeneratedFormatWithoutProof(String text) {
        assertThat(parse(text)).isNull();
    }

    @Test
    void readsDrivecoJson() {
        SourcePrice price = parse("{\"fixedPrice\":0,\"energyPrice\":0.51,\"minimumBilling\":0,\"matrix\":[],"
                + "\"matrixOSF\":[{\"duration\":0,\"interval\":1,\"price\":0.2,\"gracePeriodBeforeOSF\":900}],"
                + "\"hasDynamicTarif\":false,\"ecoHour\":false}");

        assertThat(price.energyPerKwh()).isEqualByComparingTo("0.51");
        assertThat(price.sessionFee()).isNull();
        assertThat(price.furtherFees()).isTrue();
        assertThat(parse("{\"energyPrice\":0.4,\"hasDynamicTarif\":true}")).isNull();
        assertThat(parse("{\"energyPrice\":")).isNull();
    }

    @Test
    void theFreeFlagMeansFreeUnlessTheTextNamesAPrice() {
        assertThat(IrvePriceText.parse("", true, WHEN).free()).isTrue();
        assertThat(IrvePriceText.parse("Gratuit", false, WHEN).free()).isTrue();
        assertThat(IrvePriceText.parse("0,29€ / kWh", true, WHEN)).isNull();
        assertThat(IrvePriceText.parse("", false, WHEN)).isNull();
        assertThat(IrvePriceText.parse(null, false, WHEN)).isNull();
    }

    @ParameterizedTest(name = "{0} is net → {1}")
    @CsvSource({"0.3333,0.40", "0.4667,0.56", "0.325,0.39", "0.30916667,0.371", "0.45833,0.55"})
    void provesNetAmounts(String net, String gross) {
        assertThat(IrvePriceText.provenNet(new BigDecimal(net))).isEqualByComparingTo(gross);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.42", "0.5", "0.33334", "0.3342", "0.43333334"})
    void doesNotProveRoundOrUnexplainedAmounts(String net) {
        assertThat(IrvePriceText.provenNet(new BigDecimal(net))).isNull();
    }
}
