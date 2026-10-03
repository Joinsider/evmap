package de.joinside.evmap_service.sync.mobilithek;

import com.fasterxml.jackson.databind.JsonNode;
import de.joinside.evmap_service.mobilithek.Datex;
import de.joinside.evmap_service.sync.SourceStation.EnergyWindow;
import de.joinside.evmap_service.sync.SourceStation.SourcePrice;
import de.joinside.evmap_service.sync.SourceStation.TimeFee;
import de.joinside.evmap_service.sync.SourceStation.TimeWindow;
import de.joinside.evmap_service.vatbasis.OperatorBasis;
import de.joinside.evmap_service.vatbasis.TableBasis;
import de.joinside.evmap_service.vatbasis.VatBasisTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ad-hoc rates of the static AFIR feeds, in the publishers' own shapes of 2026-10-03 (ADR 0022, L6p).
 */
class AfirPriceReaderTests {
    private static final Instant AS_OF = Instant.parse("2026-10-03T15:30:00Z");
    private static final String STATED_BY = "Test via Mobilithek";

    private static VatBasisTable table(String operator, TableBasis basis) {
        return VatBasisTable.of(List.of(new OperatorBasis(operator, basis, LocalDate.parse("2026-10-02"), "test")));
    }

    private static AfirPriceReader reader() {
        return new AfirPriceReader(VatBasisTable.of(List.of()), STATED_BY);
    }

    /** A refill point carrying {@code rates}, each a JSON {@code energyRate}. */
    private static JsonNode point(String... rates) throws IOException {
        return Datex.JSON.readTree("{\"electricEnergy\": [{\"energyRate\": [" + String.join(",", rates) + "]}]}");
    }

    private static String rate(String prices) {
        return rate(prices, "");
    }

    private static String rate(String prices, String extra) {
        return "{\"ratePolicy\": {\"value\": \"adHoc\"}, \"lastUpdated\": \"2026-09-19T13:05:12.127+02:00\", "
                + "\"applicableCurrency\": [\"EUR\"]" + extra + ", \"energyPrice\": [" + prices + "]}";
    }

    private static List<SourcePrice> read(AfirPriceReader reader, String operator, String... rates) throws IOException {
        return reader.read(point(rates), operator, AS_OF);
    }

    private static SourcePrice single(String... rates) throws IOException {
        List<SourcePrice> prices = read(reader(), "Operator", rates);
        assertThat(prices).hasSize(1);
        return prices.getFirst();
    }

    private static final String DAY = """
            "overallPeriod": {"overallStartTime": "2026-09-23T14:55:14+00:00", "validPeriod": [
              {"recurringTimePeriodOfDay": [{"startTimeOfPeriod": "08:00:00+00:00", "endTimeOfPeriod": "20:00:00+00:00"}]}]}""";
    private static final String NIGHT = """
            "overallPeriod": {"overallStartTime": "2026-09-23T14:55:14+00:00", "validPeriod": [
              {"recurringTimePeriodOfDay": [{"startTimeOfPeriod": "20:00:00+00:00", "endTimeOfPeriod": "08:00:00+00:00"}]}]}""";

    @Test
    @DisplayName("EnBW: net amounts with their rate become gross, the fee per minute too")
    void netWithRate() throws IOException {
        SourcePrice price = single(rate("""
                {"priceType": {"value": "pricePerKWh"}, "value": 0.66386555, "taxIncluded": false, "taxRate": 19.0},
                {"priceType": {"value": "pricePerMinute"}, "value": 0.20168067233333334, "taxIncluded": false,
                 "taxRate": 19.0, "timeBasedApplicability": {"fromMinute": 30, "toMinute": 0}}""",
                ", \"payment\": {\"paymentMeans\": [{\"value\": \"nfc\"}, {\"value\": \"website\"}]}"));

        assertThat(price.energyPerKwh()).isEqualByComparingTo("0.79");
        assertThat(price.timeFees()).singleElement().satisfies(fee -> {
            assertThat(fee.fromMinute()).isEqualTo(30);
            assertThat(fee.toMinute()).isNull();
            assertThat(fee.perMinute()).isEqualByComparingTo("0.24");
            assertThat(fee.window()).isNull();
        });
        assertThat(price.vatBasisStated()).isTrue();
        assertThat(price.paymentMeans()).containsExactly("nfc", "website");
        assertThat(price.observedAt()).isEqualTo(Instant.parse("2026-09-19T11:05:12.127Z"));
        assertThat(price.statedBy()).isEqualTo(STATED_BY);
    }

    @Test
    @DisplayName("Wirelane: a start fee without a flag takes its rate's, and a capped fee keeps its local window")
    void inheritedFlagWindowAndCap() throws IOException {
        SourcePrice price = single(rate("""
                {"priceType": {"value": "basePrice"}, "value": 1.0},
                {"priceType": {"value": "pricePerKWh"}, "value": 0.5, "taxIncluded": true, "taxRate": 19.0,
                 "overallPeriod": {"overallStartTime": "2026-09-23T14:55:14+00:00"}},
                {"priceType": {"value": "pricePerMinute"}, "value": 0.0, "taxIncluded": true, "taxRate": 19.0,
                 "timeBasedApplicability": {"fromMinute": 0, "toMinute": 240}},
                {"priceType": {"value": "pricePerMinute"}, "value": 0.1, "priceCap": 12.0, "taxIncluded": true,
                 "taxRate": 19.0, %s, "timeBasedApplicability": {"fromMinute": 240}},
                {"priceType": {"value": "pricePerMinute"}, "value": 0.0, "taxIncluded": true, "taxRate": 19.0,
                 %s, "timeBasedApplicability": {"fromMinute": 240}}""".formatted(DAY, NIGHT)));

        assertThat(price.energyPerKwh()).isEqualByComparingTo("0.5");
        assertThat(price.sessionFee()).isEqualByComparingTo("1");
        // Local time as written: Wirelane's own text says "außer zwischen 20–8 Uhr".
        assertThat(price.timeFees()).containsExactly(new TimeFee(240, null, new BigDecimal("0.1"), new BigDecimal("12"),
                new TimeWindow("08:00", "20:00", List.of())));
    }

    @Test
    @DisplayName("Wirelane: a blocking fee written as an energy price from minute 240 is not understood")
    void energyPriceThatChangesWithTheMinute() throws IOException {
        AfirPriceReader reader = reader();
        assertThat(read(reader, "Wirelane", rate("""
                {"priceType": {"value": "pricePerKWh"}, "value": 0.5, "taxIncluded": true, "taxRate": 19.0},
                {"priceType": {"value": "pricePerKWh"}, "value": 0.0, "taxIncluded": true, "taxRate": 19.0,
                 "timeBasedApplicability": {"fromMinute": 0, "toMinute": 240}},
                {"priceType": {"value": "pricePerKWh"}, "value": 0.1, "priceCap": 12.0, "taxIncluded": true,
                 "taxRate": 19.0, %s, "timeBasedApplicability": {"fromMinute": 240}}""".formatted(DAY)))).isEmpty();
        assertThat(reader.counters.uncertain).isOne();
    }

    @Test
    @DisplayName("Wirelane: a fee whose own end nothing takes over from (\"ab 120 Min.\" as 0 to 120) is not understood")
    void feeThatEndsByItself() throws IOException {
        assertThat(read(reader(), "Wirelane", rate("""
                {"priceType": {"value": "pricePerKWh"}, "value": 0.7, "taxIncluded": true, "taxRate": 19.0},
                {"priceType": {"value": "pricePerMinute"}, "value": 0.1, "priceCap": 12.0, "taxIncluded": true,
                 "taxRate": 19.0, "timeBasedApplicability": {"fromMinute": 0, "toMinute": 120}}"""))).isEmpty();
    }

    @Test
    @DisplayName("chargecloud: a fee ends where a zero fee of its window takes over; weekdays and day ends are read")
    void feeThatEndsAndWeekdays() throws IOException {
        String weekdays = """
                "overallPeriod": {"validPeriod": [{
                  "recurringTimePeriodOfDay": [{"startTimeOfPeriod": "08:00:00+02:00", "endTimeOfPeriod": "19:59:59+02:00"}],
                  "recurringDayWeekMonthPeriod": [{"comDayWeekMonth": {"applicableDay": [
                    {"value": "monday"}, {"value": "tuesday"}, {"value": "wednesday"}, {"value": "thursday"},
                    {"value": "friday"}, {"value": "saturday"}]}}]}]}""";
        SourcePrice price = single(rate("""
                {"priceType": {"value": "pricePerKWh"}, "value": 0.49, "taxIncluded": true},
                {"priceType": {"value": "pricePerMinute"}, "value": 0},
                {"priceType": {"value": "pricePerMinute"}, "value": 0},
                {"priceType": {"value": "pricePerMinute"}, "value": 0.1, %1$s, "timeBasedApplicability": {"fromMinute": 240}},
                {"priceType": {"value": "pricePerMinute"}, "value": 0.1, %1$s, "timeBasedApplicability": {"fromMinute": 240}},
                {"priceType": {"value": "pricePerMinute"}, "value": 0, %1$s, "timeBasedApplicability": {"fromMinute": 390}}"""
                .formatted(weekdays)));

        assertThat(price.timeFees()).containsExactly(new TimeFee(240, 390, new BigDecimal("0.1"), null,
                new TimeWindow("08:00", "20:00", List.of("monday", "tuesday", "wednesday", "thursday", "friday",
                        "saturday"))));
    }

    @Test
    @DisplayName("an energy price per time of day is kept per window, every day as no window at all")
    void energyWindows() throws IOException {
        String allWeek = """
                "overallPeriod": {"validPeriod": [{
                  "recurringTimePeriodOfDay": [{"startTimeOfPeriod": "00:00:00+00:00", "endTimeOfPeriod": "23:59:00+00:00"}],
                  "recurringDayWeekMonthPeriod": [{"comDayWeekMonth": {"applicableDay": [
                    {"value": "monday"}, {"value": "tuesday"}, {"value": "wednesday"}, {"value": "thursday"},
                    {"value": "friday"}, {"value": "saturday"}, {"value": "sunday"}]}}]}]}""";
        SourcePrice price = single(rate("""
                {"priceType": {"value": "pricePerKWh"}, "value": 0.4499999881, "taxIncluded": true, "taxRate": 19, %s},
                {"priceType": {"value": "pricePerKWh"}, "value": 0.5500000119, "taxIncluded": true, "taxRate": 19, %s},
                {"priceType": {"value": "pricePerMinute"}, "value": 0.0200000008, "taxIncluded": true, "taxRate": 19, %s}"""
                .formatted(NIGHT, DAY, allWeek)));

        assertThat(price.energyPerKwh()).isNull();
        assertThat(price.energyWindows()).containsExactly(
                new EnergyWindow(new BigDecimal("0.55"), new TimeWindow("08:00", "20:00", List.of())),
                new EnergyWindow(new BigDecimal("0.45"), new TimeWindow("20:00", "08:00", List.of())));
        assertThat(price.timeFees()).containsExactly(new TimeFee(0, null, new BigDecimal("0.02"), null, null));
    }

    @Test
    @DisplayName("an energy price per window next to one for all hours is two prices for one moment")
    void windowedNextToUnwindowedEnergy() throws IOException {
        assertThat(read(reader(), "Operator", rate("""
                {"priceType": {"value": "pricePerKWh"}, "value": 0.5, "taxIncluded": true},
                {"priceType": {"value": "pricePerKWh"}, "value": 0.4, "taxIncluded": true, %s}""".formatted(NIGHT))))
                .isEmpty();
    }

    @Test
    @DisplayName("a fee for all hours next to a different one for some hours, from the same minute, is not understood")
    void twoFeesForOneMoment() throws IOException {
        assertThat(read(reader(), "Operator", rate("""
                {"priceType": {"value": "pricePerKWh"}, "value": 0.46, "taxIncluded": true},
                {"priceType": {"value": "pricePerMinute"}, "value": 0, "taxIncluded": true, %s},
                {"priceType": {"value": "pricePerMinute"}, "value": 0.07, "taxIncluded": true}""".formatted(DAY))))
                .isEmpty();
    }

    @Test
    @DisplayName("chargecloud without a flag: the table decides, rounded to the cent, and an unlisted operator gets none")
    void unstatedBasis() throws IOException {
        String gross = rate("""
                {"priceType": {"value": "flatRate"}, "value": 1.5},
                {"priceType": {"value": "pricePerKWh"}, "value": 0.5355}""");
        AfirPriceReader reader = new AfirPriceReader(table("Stadtwerke Test", TableBasis.GROSS), STATED_BY);

        SourcePrice price = read(reader, "Stadtwerke Test", gross).getFirst();
        assertThat(price.energyPerKwh()).isEqualByComparingTo("0.54");
        assertThat(price.sessionFee()).isEqualByComparingTo("1.5");
        assertThat(price.vatBasisStated()).isFalse();

        assertThat(read(reader, "Unlisted GmbH", gross)).isEmpty();
        assertThat(reader.counters.basisUnknown).isOne();

        AfirPriceReader net = new AfirPriceReader(table("Netto AG", TableBasis.NET), STATED_BY);
        assertThat(read(net, "Netto AG", rate("{\"priceType\": {\"value\": \"pricePerKWh\"}, \"value\": 0.49}"))
                .getFirst().energyPerKwh()).isEqualByComparingTo("0.58");
    }

    @Test
    @DisplayName("without a flag, a net price that lands on whole cents with its rate is proven net")
    void provenNet() throws IOException {
        SourcePrice price = single(rate("""
                {"priceType": {"value": "pricePerKWh"}, "value": 0.4622, "taxRate": 19}"""));

        assertThat(price.energyPerKwh()).isEqualByComparingTo("0.55");
        assertThat(price.vatBasisStated()).isFalse();
    }

    @Test
    @DisplayName("an operator listed as gross whose price the arithmetic proves net loses its entry for the run")
    void contradictedEntry() throws IOException {
        AfirPriceReader reader = new AfirPriceReader(table("Widerspruch GmbH", TableBasis.GROSS), STATED_BY);

        assertThat(read(reader, "Widerspruch GmbH",
                rate("{\"priceType\": {\"value\": \"pricePerKWh\"}, \"value\": 0.4622}"))).isEmpty();
        // Suspended: a round price that would have passed before now gets none either.
        assertThat(read(reader, "Widerspruch GmbH",
                rate("{\"priceType\": {\"value\": \"pricePerKWh\"}, \"value\": 0.49}"))).isEmpty();
        assertThat(reader.counters.basisUnknown).isEqualTo(2);
    }

    @Test
    @DisplayName("several ad-hoc rates: identical ones become one with all means, different ones stay apart")
    void severalRates() throws IOException {
        String qr = rate("{\"priceType\": {\"value\": \"pricePerKWh\"}, \"value\": 0.5, \"taxIncluded\": true},"
                        + "{\"priceType\": {\"value\": \"basePrice\"}, \"value\": 1.99, \"taxIncluded\": true}",
                ", \"payment\": {\"paymentMeans\": [{\"value\": \"qrCode\"}]}");
        String app = rate("{\"priceType\": {\"value\": \"pricePerKWh\"}, \"value\": 0.5, \"taxIncluded\": true},"
                        + "{\"priceType\": {\"value\": \"basePrice\"}, \"value\": 1.99, \"taxIncluded\": true}",
                ", \"payment\": {\"paymentMeans\": [{\"value\": \"mobileAccount\"}, {\"value\": \"qrCode\"}]}");
        String card = rate("{\"priceType\": {\"value\": \"pricePerKWh\"}, \"value\": 0.45, \"taxIncluded\": false, "
                + "\"taxRate\": 19.0}", ", \"payment\": {\"paymentMeans\": [{\"value\": \"emv\"}]}");
        String contract = "{\"ratePolicy\": {\"value\": \"contract\"}, \"applicableCurrency\": [\"EUR\"], "
                + "\"energyPrice\": [{\"priceType\": {\"value\": \"pricePerKWh\"}, \"value\": 0.3}]}";

        List<SourcePrice> prices = read(reader(), "eRound", qr, app, card, contract);

        assertThat(prices).hasSize(2);
        assertThat(prices.get(0).paymentMeans()).containsExactly("qrCode", "mobileAccount");
        assertThat(prices.get(0).sessionFee()).isEqualByComparingTo("1.99");
        assertThat(prices.get(1).paymentMeans()).containsExactly("emv");
        assertThat(prices.get(1).energyPerKwh()).isEqualByComparingTo("0.5355");
    }

    @Test
    @DisplayName("evprice: a schedule of dated slots is not read; gridco: last season's price is left out")
    void datedPrices() throws IOException {
        assertThat(read(reader(), "EW Pricing", rate("""
                {"priceType": {"value": "pricePerKWh"}, "value": 0.56, "taxIncluded": true, "taxRate": 19.0,
                 "overallPeriod": {"overallStartTime": "2026-10-03T13:45:00Z", "overallEndTime": "2026-10-03T15:00:00Z"}},
                {"priceType": {"value": "pricePerKWh"}, "value": 0.66, "taxIncluded": true, "taxRate": 19.0,
                 "overallPeriod": {"overallStartTime": "2026-10-03T15:00:00Z"}}"""))).isEmpty();
        assertThat(read(reader(), "EW Pricing", rate("""
                {"priceType": {"value": "pricePerKWh"}, "value": 0.63, "taxIncluded": true, "taxRate": 19.0,
                 "overallPeriod": {"overallStartTime": "2026-10-03T21:30:00Z"}}"""))).isEmpty();

        SourcePrice gridco = single(rate("""
                {"priceType": {"value": "pricePerKWh"}, "value": 0.7900000215, "taxIncluded": true, "taxRate": 19.0},
                {"priceType": {"value": "pricePerMinute"}, "value": 0.0400000016, "taxIncluded": true, "taxRate": 19.0,
                 "overallPeriod": {"overallStartTime": "2026-02-19T14:15:47.19Z", "overallEndTime": "2026-09-03T21:59:59.999Z"},
                 "timeBasedApplicability": {"fromMinute": 15}}"""));
        assertThat(gridco.energyPerKwh()).isEqualByComparingTo("0.79");
        assertThat(gridco.timeFees()).isEmpty();
    }

    @Test
    @DisplayName("Monta: an idle fee after charging carries no amount, only \"further fees\"")
    void idleFee() throws IOException {
        SourcePrice price = single(rate("""
                {"priceType": {"value": "pricePerKWh"}, "value": 0.69, "taxIncluded": true, "taxRate": 19},
                {"priceType": {"value": "other"}, "value": 0.15, "taxIncluded": true, "taxRate": 19,
                 "additionalInformation": {"values": [{"lang": "en", "value": "Idle fee applies 30 minutes after charging has ended."}]},
                 "timeBasedApplicability": {"fromMinute": 30}}"""));

        assertThat(price.energyPerKwh()).isEqualByComparingTo("0.69");
        assertThat(price.timeFees()).isEmpty();
        assertThat(price.furtherFees()).isTrue();
    }

    @Test
    @DisplayName("amounts outside the plausible bands: no price, or a fee without its number")
    void bands() throws IOException {
        assertThat(read(reader(), "Monta", rate("""
                {"priceType": {"value": "pricePerKWh"}, "value": 2.0, "taxIncluded": true}"""))).isEmpty();
        assertThat(read(reader(), "Monta", rate("""
                {"priceType": {"value": "pricePerKWh"}, "value": 0.48, "taxIncluded": true},
                {"priceType": {"value": "basePrice"}, "value": 25.0, "taxIncluded": true}"""))).isEmpty();
        assertThat(read(reader(), "Monta", rate("""
                {"priceType": {"value": "pricePerKWh"}, "value": 0.48, "taxIncluded": true},
                {"priceType": {"value": "basePrice"}, "value": 10.0, "taxIncluded": true,
                 "timeBasedApplicability": {"fromMinute": 90}}"""))).isEmpty();

        // eRound: 0,10 €/h divided once too often.
        SourcePrice price = single(rate("""
                {"priceType": {"value": "pricePerKWh"}, "value": 0.59, "taxIncluded": true},
                {"priceType": {"value": "pricePerMinute"}, "value": 0.001666667, "taxIncluded": true,
                 "timeBasedApplicability": {"fromMinute": 60}}"""));
        assertThat(price.timeFees()).containsExactly(new TimeFee(60, null, null, null, null));
    }

    @Test
    @DisplayName("nothing to pay is free; no energy price, another currency or an unknown type is no price")
    void freeAndRejected() throws IOException {
        SourcePrice free = single(rate("""
                {"priceType": {"value": "pricePerKWh"}, "value": 0, "taxIncluded": true, "taxRate": 19}"""));
        assertThat(free.free()).isTrue();
        assertThat(free.energyPerKwh()).isNull();

        assertThat(read(reader(), "PUMP", rate(""))).isEmpty();
        assertThat(read(reader(), "PUMP", rate("""
                {"priceType": {"value": "basePrice"}, "value": 1, "taxIncluded": true}"""))).isEmpty();
        assertThat(read(reader(), "PUMP", rate("""
                {"priceType": {"value": "pricePerKWh"}, "value": 0.5, "taxIncluded": true},
                {"priceType": {"value": "pricePerHour"}, "value": 6, "taxIncluded": true}"""))).isEmpty();
        assertThat(read(reader(), "PUMP", "{\"ratePolicy\": {\"value\": \"adHoc\"}, \"applicableCurrency\": [\"CHF\"], "
                + "\"energyPrice\": [{\"priceType\": {\"value\": \"pricePerKWh\"}, \"value\": 0.5}]}")).isEmpty();
        assertThat(read(reader(), "PUMP", rate("""
                {"priceType": {"value": "pricePerKWh"}, "value": 0.5, "taxIncluded": true},
                {"priceType": {"value": "basePrice"}, "value": 1, "taxIncluded": false, "taxRate": 19}"""))).isEmpty();
        assertThat(read(reader(), "PUMP", rate("""
                {"priceType": {"value": "pricePerKWh"}, "value": 0.5, "taxIncluded": false}"""))).isEmpty();
        assertThat(read(reader(), "PUMP", rate("""
                {"priceType": {"value": "pricePerKWh"}, "value": 0.5, "taxIncluded": true,
                 "energyBasedApplicability": {"fromKWh": 10}}"""))).isEmpty();
        assertThat(reader().read(Datex.JSON.readTree("{}"), "PUMP", AS_OF)).isEmpty();
    }

    @Test
    @DisplayName("empty extension objects are no applicability, a validity exception is")
    void extensions() throws IOException {
        SourcePrice price = single(rate("""
                {"priceType": {"value": "pricePerKWh"}, "value": 0.65, "taxIncluded": true,
                 "overallPeriod": {"overallStartTime": "2025-09-15T12:10:25Z", "validPeriod": [], "exceptionPeriod": [],
                   "comOverallPeriodExtensionG": {}}, "aegiEnergyPriceExtensionG": {}}""",
                ", \"aegiEnergyRateExtensionG\": {}"));
        assertThat(price.energyPerKwh()).isEqualByComparingTo("0.65");

        assertThat(read(reader(), "SMATRICS", rate("""
                {"priceType": {"value": "pricePerKWh"}, "value": 0.65, "taxIncluded": true,
                 "overallPeriod": {"exceptionPeriod": [{"recurringTimePeriodOfDay": [
                   {"startTimeOfPeriod": "00:00:00", "endTimeOfPeriod": "06:00:00"}]}]}}"""))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "08:00:00+02:00, false, 08:00",
            "19:59:59+02:00, true, 20:00",
            "23:59:00+00:00, true, 24:00",
            "23:59:59, true, 24:00",
            "00:00:00+00:00, true, 24:00",
            "07:00, false, 07:00",
            "25:00:00, false, ",
            "noon, false, "})
    @DisplayName("clock times: local as written, an end on the last second or minute rounds up")
    void clock(String published, boolean end, String expected) {
        assertThat(AfirPriceReader.clock(published, end)).isEqualTo(expected);
    }

    @Test
    @DisplayName("the site mapper attaches the prices to their charge points, read from XML like from JSON")
    void throughTheMapper() throws IOException {
        String xml = """
                <d2:payload xmlns:d2="http://datex2.eu/schema/3/d2Payload" xmlns:egi="http://datex2.eu/schema/3/energyInfrastructure">
                 <egi:energyInfrastructureTable id="t"><egi:energyInfrastructureSite id="site-1">
                  <fac:locationReference xmlns:fac="http://datex2.eu/schema/3/facilities"><loc:coordinatesForDisplay xmlns:loc="http://datex2.eu/schema/3/locationReferencing">
                   <loc:latitude>51.2</loc:latitude><loc:longitude>6.8</loc:longitude></loc:coordinatesForDisplay></fac:locationReference>
                  <egi:energyInfrastructureStation id="st-1">
                   <egi:refillPoint id="DE*LDN*E1">
                    <egi:electricEnergy><egi:energyRate id="r">
                     <egi:ratePolicy>adHoc</egi:ratePolicy><egi:applicableCurrency>EUR</egi:applicableCurrency>
                     <egi:energyPrice><egi:priceType>pricePerKWh</egi:priceType><egi:value>0.59</egi:value>
                      <egi:taxIncluded>true</egi:taxIncluded><egi:taxRate>19</egi:taxRate></egi:energyPrice>
                    </egi:energyRate></egi:electricEnergy>
                    <egi:connector><egi:connectorType>iec62196T2</egi:connectorType></egi:connector>
                   </egi:refillPoint>
                  </egi:energyInfrastructureStation>
                 </egi:energyInfrastructureSite></egi:energyInfrastructureTable>
                </d2:payload>
                """;
        AfirSiteMapper mapper = new AfirSiteMapper("MOBILITHEK", new MobilithekSyncProperties.Feed("ladenetz", "1",
                "ladenetz.de"), "BNetzA", "DE", new HashSet<>(), AS_OF, reader());
        List<de.joinside.evmap_service.sync.SourceStation> stations = new ArrayList<>();
        AfirSiteReader.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                site -> stations.addAll(mapper.map(site)));

        assertThat(stations).singleElement().satisfies(station -> assertThat(station.chargePoints()).singleElement()
                .satisfies(chargePoint -> assertThat(chargePoint.prices()).singleElement()
                        .satisfies(price -> assertThat(price.energyPerKwh()).isEqualByComparingTo("0.59"))));
    }
}
