package de.joinside.evmap_service.sync.es;

import de.joinside.evmap_service.sync.SourceStation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Covers what the parser tests cannot: that the configured URL is fetched, that accented text survives
 * the download (the register is UTF-8 and the reader decodes strictly), and that a failed download
 * leaves nothing half-done.
 */
class MiterdSourceAdapterTests {
    private static final String URL = "https://nap.dgt.es/datex2/v3/register.xml";
    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    private static final String XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d2:payload xmlns:d2="http://datex2.eu/schema/3/d2Payload" xmlns:com="http://datex2.eu/schema/3/common"
              xmlns:loc="http://datex2.eu/schema/3/locationReferencing" xmlns:egi="http://datex2.eu/schema/3/energyInfrastructure"
              xmlns:fac="http://datex2.eu/schema/3/facilities" xmlns:locx="http://datex2.eu/schema/3/locationExtension">
             <egi:energyInfrastructureTable id="ELECTROLINERAS">
              <egi:energyInfrastructureSite id="2023002088">
               <fac:name><com:values><com:value lang="es">Centro Porsche</com:value></com:values></fac:name>
               <fac:locationReference>
                <loc:_locationReferenceExtension><loc:facilityLocation><locx:address><locx:postcode>7011</locx:postcode>
                 <locx:addressLine order="2"><locx:text><com:values><com:value lang="es">Municipio: Alcalá de Henares</com:value></com:values></locx:text></locx:addressLine>
                </locx:address></loc:facilityLocation></loc:_locationReferenceExtension>
                <loc:coordinatesForDisplay><loc:latitude>39.5948</loc:latitude><loc:longitude>2.63586</loc:longitude></loc:coordinatesForDisplay>
               </fac:locationReference>
               <egi:energyInfrastructureStation id="2023002088_1">
                <egi:refillPoint id="COD1"><fac:name><com:values><com:value lang="es">ES*915*EES0001</com:value></com:values></fac:name>
                 <egi:connector><egi:connectorType>iec62196T2</egi:connectorType><egi:maxPowerAtSocket>22000.0</egi:maxPowerAtSocket></egi:connector>
                </egi:refillPoint>
               </egi:energyInfrastructureStation>
              </egi:energyInfrastructureSite>
             </egi:energyInfrastructureTable>
            </d2:payload>
            """;

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    private MiterdSourceAdapter adapter() {
        return new MiterdSourceAdapter(new MiterdProperties(true, URL, Duration.ofSeconds(5)), builder.build(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static List<SourceStation> drain(Stream<SourceStation> stations) {
        try (stations) {
            return stations.toList();
        }
    }

    @Test
    @DisplayName("names its source and fetches the configured URL")
    void fetchesTheConfiguredUrl() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess(XML.getBytes(StandardCharsets.UTF_8), MediaType.TEXT_XML));

        assertThat(adapter().source()).isEqualTo("MITERD");
        assertThat(drain(adapter().fetchStations())).singleElement().satisfies(station -> {
            assertThat(station.source()).isEqualTo("MITERD");
            assertThat(station.city()).isEqualTo("Alcalá de Henares");
            assertThat(station.chargePoints()).singleElement()
                    .extracting(SourceStation.SourceChargePoint::evseId).isEqualTo("ES*915*EES0001");
        });
        server.verify();
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
        server.expect(requestTo(URL)).andRespond(withSuccess("<html><body>moved</body></html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> adapter().fetchStations())
                .isInstanceOf(UncheckedIOException.class).hasMessageContaining("energyInfrastructureSite");
    }

    @Test
    @DisplayName("can be switched off")
    void canBeDisabled() {
        var disabled = new MiterdSourceAdapter(new MiterdProperties(false, URL, Duration.ofSeconds(5)), builder.build(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(disabled.enabled()).isFalse();
    }
}
