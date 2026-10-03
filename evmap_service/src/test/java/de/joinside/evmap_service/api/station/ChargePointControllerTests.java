package de.joinside.evmap_service.api.station;

import de.joinside.evmap_service.availability.Attribution;
import de.joinside.evmap_service.pricing.AdHocPrice;
import de.joinside.evmap_service.pricing.PricingService;
import de.joinside.evmap_service.pricing.StationPrices;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The payload the iOS client decodes: a price per charge point, absent amounts as null, and the credits. */
class ChargePointControllerTests {
    private final PricingService pricing = mock(PricingService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ChargePointController(pricing)).build();

    @Test
    @DisplayName("serves charge points with operator, connectors, price and the sources to credit")
    void servesPrices() throws Exception {
        UUID station = UUID.randomUUID();
        UUID priced = UUID.randomUUID();
        UUID unpriced = UUID.randomUUID();
        AdHocPrice price = new AdHocPrice("EUR", new BigDecimal("0.55"), new BigDecimal("1.5"),
                List.of(new AdHocPrice.TimeFee(240, new BigDecimal("0.1")), new AdHocPrice.TimeFee(300, null)),
                false, true, Instant.parse("2026-09-30T00:00:00Z"));
        when(pricing.forStation(station)).thenReturn(Optional.of(new StationPrices(station, new BigDecimal("0.55"),
                "EUR", List.of(
                new StationPrices.PricedChargePoint(priced, "DE*LID*E1", "Lidl",
                        List.of(new StationPrices.Connector("CCS", new BigDecimal("150"), 1)), List.of(price), "MobiData BW"),
                new StationPrices.PricedChargePoint(unpriced, null, "Partner AG", List.of(), List.of(), null)),
                List.of(new Attribution("MobiData BW", "dl-de/by-2.0", "https://www.mobidata-bw.de")))));

        mockMvc.perform(get("/api/v1/stations/{id}/charge-points", station))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cheapestEnergyPerKwh").value(0.55))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.chargePoints[0].operatorName").value("Lidl"))
                .andExpect(jsonPath("$.chargePoints[0].connectors[0].connectorType").value("CCS"))
                .andExpect(jsonPath("$.chargePoints[0].price.energyPerKwh").value(0.55))
                .andExpect(jsonPath("$.chargePoints[0].price.sessionFee").value(1.5))
                .andExpect(jsonPath("$.chargePoints[0].price.timeFees[0].fromMinute").value(240))
                .andExpect(jsonPath("$.chargePoints[0].price.timeFees[1].perMinute").doesNotExist())
                .andExpect(jsonPath("$.chargePoints[0].price.furtherFees").value(true))
                .andExpect(jsonPath("$.chargePoints[0].price.source").value("MobiData BW"))
                .andExpect(jsonPath("$.chargePoints[1].price").doesNotExist())
                .andExpect(jsonPath("$.sources[0].licence").value("dl-de/by-2.0"));
    }

    @Test
    @DisplayName("several prices, windows, ends and caps go into prices; the single price only where an old client can show it")
    void servesPriceDetails() throws Exception {
        UUID station = UUID.randomUUID();
        AdHocPrice.TimeWindow day = new AdHocPrice.TimeWindow("08:00", "20:00", List.of("monday"));
        AdHocPrice qr = new AdHocPrice("EUR", new BigDecimal("0.5"), List.of(), null,
                List.of(new AdHocPrice.TimeFee(240, 390, new BigDecimal("0.1"), new BigDecimal("12"), day)),
                false, false, null, List.of("qrCode"));
        AdHocPrice card = new AdHocPrice("EUR", null,
                List.of(new AdHocPrice.EnergyWindow(new BigDecimal("0.59"), day)), null, List.of(), false, false, null,
                List.of("emv"));
        AdHocPrice plain = new AdHocPrice("EUR", new BigDecimal("0.49"), null, List.of(), false, false, null);
        when(pricing.forStation(station)).thenReturn(Optional.of(new StationPrices(station, new BigDecimal("0.49"), "EUR",
                List.of(new StationPrices.PricedChargePoint(UUID.randomUUID(), "DE*GRD*E1", "Grid", List.of(),
                                List.of(qr, card), "Grid & Co. GmbH via Mobilithek"),
                        new StationPrices.PricedChargePoint(UUID.randomUUID(), "DE*GRD*E2", "Grid", List.of(),
                                List.of(qr), "Grid & Co. GmbH via Mobilithek"),
                        new StationPrices.PricedChargePoint(UUID.randomUUID(), "DE*GRD*E3", "Grid", List.of(),
                                List.of(plain), "IRVE")),
                List.of())));

        mockMvc.perform(get("/api/v1/stations/{id}/charge-points", station))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chargePoints[0].price").doesNotExist())
                .andExpect(jsonPath("$.chargePoints[0].prices.length()").value(2))
                .andExpect(jsonPath("$.chargePoints[0].prices[0].paymentMeans[0]").value("qrCode"))
                .andExpect(jsonPath("$.chargePoints[0].prices[0].timeFees[0].toMinute").value(390))
                .andExpect(jsonPath("$.chargePoints[0].prices[0].timeFees[0].cap").value(12))
                .andExpect(jsonPath("$.chargePoints[0].prices[0].timeFees[0].window.from").value("08:00"))
                .andExpect(jsonPath("$.chargePoints[0].prices[0].timeFees[0].window.days[0]").value("monday"))
                .andExpect(jsonPath("$.chargePoints[0].prices[1].energyWindows[0].perKwh").value(0.59))
                .andExpect(jsonPath("$.chargePoints[0].prices[1].source").value("Grid & Co. GmbH via Mobilithek"))
                // One price, but limited in a way an old client would drop: only in prices.
                .andExpect(jsonPath("$.chargePoints[1].price").doesNotExist())
                .andExpect(jsonPath("$.chargePoints[1].prices.length()").value(1))
                .andExpect(jsonPath("$.chargePoints[2].price.energyPerKwh").value(0.49))
                .andExpect(jsonPath("$.chargePoints[2].prices[0].energyPerKwh").value(0.49));
    }

    @Test
    @DisplayName("an unknown station raises the exception the API answers with 404")
    void anUnknownStationIsNotFound() {
        UUID station = UUID.randomUUID();
        when(pricing.forStation(station)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mockMvc.perform(get("/api/v1/stations/{id}/charge-points", station)))
                .hasCauseInstanceOf(StationController.StationNotFoundException.class);
    }
}
