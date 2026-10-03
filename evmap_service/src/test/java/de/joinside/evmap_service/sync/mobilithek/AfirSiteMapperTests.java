package de.joinside.evmap_service.sync.mobilithek;

import de.joinside.evmap_service.sync.SourceStation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The reader and the mapper together, on the dialects of real publishers ({@link AfirFixtures}). */
class AfirSiteMapperTests {
    private final Set<String> emitted = new HashSet<>();

    private AfirSiteMapper mapper(String key, String publisher) {
        return new AfirSiteMapper("MOBILITHEK", new MobilithekSyncProperties.Feed(key, "1", publisher), "BNetzA", "DE",
                emitted);
    }

    private static List<SourceStation> read(AfirSiteMapper mapper, String body) throws IOException {
        List<SourceStation> stations = new ArrayList<>();
        AfirSiteReader.read(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
                site -> stations.addAll(mapper.map(site)));
        return stations;
    }

    private static SourceStation.SourceConnector connector(String type, String kw, int quantity) {
        return new SourceStation.SourceConnector(type, kw == null ? null : new BigDecimal(kw), quantity);
    }

    @Test
    @DisplayName("one station per AFIR station, located and addressed from its site, linked to the register")
    void mapsSiteLevelDialect() throws IOException {
        AfirSiteMapper mapper = mapper("ewe", "EWE Go GmbH");
        List<SourceStation> stations = read(mapper, AfirFixtures.EWE_JSON);

        assertThat(stations).extracting(SourceStation::sourceStationId).containsExactly("ewe/station-1", "ewe/station-2");
        SourceStation first = stations.getFirst();
        assertThat(first.source()).isEqualTo("MOBILITHEK");
        assertThat(first.latitude()).isEqualTo(53.1464157);
        assertThat(first.longitude()).isEqualTo(8.2167098);
        assertThat(first.street()).isEqualTo("Donnerschweer Straße 22-26");
        assertThat(first.city()).isEqualTo("Oldenburg (Oldb.)");
        assertThat(first.postalCode()).isEqualTo("26123");
        assertThat(first.countryCode()).isEqualTo("DE");
        // "DE*EWE" is an operator id, "000501" a code: the legal name labels both.
        assertThat(first.operatorName()).isEqualTo("EWE Go GmbH");
        assertThat(first.name()).isEqualTo("EWE Go GmbH");
        assertThat(first.namesStation()).isFalse();
        assertThat(first.lastUpdatedAt()).isEqualTo(Instant.parse("2026-09-16T06:00:17.602Z"));
        assertThat(first.links()).containsExactly(new SourceStation.SourceLink("BNetzA", "1115028"));
        assertThat(first.chargePoints()).extracting(SourceStation.SourceChargePoint::sourceChargePointId)
                .containsExactly("DEEWEE000501S0401", "DEEWEE000501S0402");
        assertThat(first.chargePoints().getFirst().evseId()).isEqualTo("DE*EWE*E000501S04*01");
        assertThat(first.chargePoints().getFirst().connectors()).containsExactly(connector("Type 2", "22", 1));

        // Two plugs of one type and rating are one connector row with quantity 2; the second station shares the site.
        SourceStation second = stations.get(1);
        assertThat(second.links()).isEmpty();
        assertThat(second.latitude()).isEqualTo(first.latitude());
        assertThat(second.chargePoints().getFirst().connectors()).containsExactly(connector("CCS", "150", 2));

        assertThat(mapper.counters.stations).isEqualTo(2);
        assertThat(mapper.counters.chargePoints).isEqualTo(3);
        assertThat(mapper.counters.linked).isOne();
        assertThat(mapper.counters.foreign).isOne();
        assertThat(mapper.counters.withoutPosition).isOne();
    }

    @Test
    @DisplayName("station-level location, EVSE-IDs as ids, and a charge point an earlier feed relayed is not repeated")
    void mapsStationLevelDialect() throws IOException {
        read(mapper("ewe", "EWE Go GmbH"), AfirFixtures.EWE_JSON);
        AfirSiteMapper enbw = mapper("enbw", "EnBW mobility+ AG und Co.KG");
        List<SourceStation> stations = read(enbw, AfirFixtures.ENBW_JSON);

        SourceStation wangen = stations.getFirst();
        assertThat(wangen.sourceStationId()).isEqualTo("enbw/13529");
        assertThat(wangen.latitude()).isEqualTo(48.718544);
        assertThat(wangen.countryCode()).isEqualTo("DE");
        assertThat(wangen.street()).isEqualTo("Siemensstraße");
        assertThat(wangen.operatorName()).isEqualTo("ENBW");
        assertThat(wangen.name()).isEqualTo("ENBW");
        // EnBW types the register's station id as operatorIdBNetzA; a non-numeric "id" is no link.
        assertThat(wangen.links()).containsExactly(new SourceStation.SourceLink("BNetzA", "1121150"));
        assertThat(wangen.chargePoints()).singleElement().satisfies(point -> {
            assertThat(point.evseId()).isEqualTo("DE*EBW*E914081*1");
            assertThat(point.connectors()).containsExactly(connector("CCS", "150", 1));
        });
        assertThat(enbw.counters.relayed).isOne();

        SourceStation elli = stations.get(1);
        // VW writes the station's name as the operator's legal name; that is no operator.
        assertThat(elli.operatorName()).isEqualTo("ENBW");
        assertThat(elli.name()).isEqualTo("Elli Box 3 Bremen");
        assertThat(elli.chargePoints()).hasSize(2);
        // GP JOULE's wrapped EVSE-ID is taken literally from between its stars; 0 W is unknown power.
        assertThat(elli.chargePoints().getFirst().sourceChargePointId()).isEqualTo("DECNTEP900460021");
        assertThat(elli.chargePoints().getFirst().connectors()).containsExactly(connector("Schuko", null, 1));
        // An internal hash is no EVSE-ID: the charge point is kept under its station, without one.
        assertThat(elli.chargePoints().get(1).evseId()).isNull();
        assertThat(elli.chargePoints().get(1).sourceChargePointId()).isEqualTo("enbw/elli-3*54bcecea3be75033a5060f0c75b86a21");
        assertThat(enbw.counters.withoutEvseId).isOne();
    }

    @Test
    @DisplayName("a station whose every charge point an earlier feed relayed is not emitted at all")
    void dropsFullyRelayedStations() throws IOException {
        read(mapper("ewe", "EWE Go GmbH"), AfirFixtures.EWE_JSON);
        assertThat(read(mapper("eclearing", "e-clearing.net"), AfirFixtures.EWE_JSON)).isEmpty();
    }

    @Test
    @DisplayName("XML reads like JSON: attributes as ids, plain and extended identifiers, the publisher for an id-like operator")
    void mapsXml() throws IOException {
        List<SourceStation> stations = read(mapper("ladenetz", "ladenetz.de"), AfirFixtures.LADENETZ_XML);

        SourceStation station = stations.getFirst();
        assertThat(station.sourceStationId()).isEqualTo("ladenetz/DESTAS0187");
        assertThat(station.latitude()).isEqualTo(50.758728);
        assertThat(station.street()).isEqualTo("Kaiser-Friedrich-Allee 5");
        assertThat(station.city()).isEqualTo("Aachen");
        assertThat(station.name()).isEqualTo("AC-Süd - Hangeweiher");
        assertThat(station.operatorName()).isEqualTo("ladenetz.de");
        assertThat(station.links()).isEmpty();
        assertThat(station.chargePoints()).extracting(SourceStation.SourceChargePoint::evseId)
                .containsExactly("DESTAE018701", "DE*STA*E018702");
        assertThat(station.chargePoints().getFirst().connectors()).containsExactly(connector("Type 2", "22", 1));
    }

    @Test
    @DisplayName("an unreadable package is an error, not an empty feed")
    void rejectsBrokenXml() {
        assertThatThrownBy(() -> read(mapper("x", "X"), "<a><energyInfrastructureSite><b></a>"))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("the accessors read either syntax's spelling and tolerate what is missing")
    void accessors() {
        assertThat(AfirSiteMapper.text(null)).isNull();
        assertThat(AfirSiteMapper.enumValue(null)).isNull();
        assertThat(AfirSiteMapper.field(null, "x")).isNull();
        assertThat(AfirSiteMapper.find(null, "x")).isNull();
        assertThat(AfirSiteMapper.items(null)).isEmpty();
        assertThat(AfirSiteMapper.items(com.fasterxml.jackson.databind.node.NullNode.getInstance())).isEmpty();
    }
}
