package de.joinside.evmap_service.availability.mobilithek;

import de.joinside.evmap_service.availability.LiveAvailability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AfirStatusParserTests {

    /**
     * Shaped after the {@code AFIR-Recharging-Dynamic-01-00-00_Delta} JSON schema: one site, two stations, a
     * station with a single refill point given as a bare object rather than an array of one.
     */
    static final String SNAPSHOT = """
            {"messageContainer": {
              "payload": [{
                "modelBaseVersionG": "3",
                "aegiEnergyInfrastructureStatusPublication": {
                  "lang": "de",
                  "publicationTime": "2026-10-02T10:00:00+02:00",
                  "publicationCreator": {"country": "DE", "nationalIdentifier": "DE-NAP-EBW"},
                  "energyInfrastructureSiteStatus": [{
                    "reference": {"targetClass": "EnergyInfrastructureSite", "idG": "SITE-1"},
                    "energyInfrastructureStationStatus": [{
                      "reference": {"targetClass": "EnergyInfrastructureStation", "idG": "STATION-1"},
                      "refillPointStatus": [
                        {"aegiElectricChargingPointStatus": {
                          "reference": {"targetClass": "RefillPoint", "idG": "DE*EBW*E1001*1"},
                          "lastUpdated": "2026-10-02T09:58:00+02:00",
                          "status": {"value": "available"}}},
                        {"aegiElectricChargingPointStatus": {
                          "reference": {"targetClass": "RefillPoint", "idG": "DE*EBW*E1001*2"},
                          "lastUpdated": "2026-10-02T09:59:00+02:00",
                          "status": {"value": "charging"}}},
                        {"aegiElectricChargingPointStatus": {
                          "reference": {"targetClass": "RefillPoint", "idG": "DE*EBW*E1001*3"},
                          "lastUpdated": "2026-10-02T09:59:30+02:00",
                          "operationStatus": {"value": "technicalDefect"},
                          "status": {"value": "available"}}}
                      ]
                    }, {
                      "reference": {"targetClass": "EnergyInfrastructureStation", "idG": "STATION-2"},
                      "refillPointStatus": {"aegiElectricChargingPointStatus": {
                          "reference": {"targetClass": "RefillPoint", "idG": "DEEBWE1002"},
                          "status": {"value": "outOfOrder"}}}
                    }, {
                      "reference": {"targetClass": "EnergyInfrastructureStation", "idG": "STATION-3"},
                      "refillPointStatus": [
                        {"aegiElectricChargingPointStatus": {
                          "reference": {"targetClass": "RefillPoint", "idG": "DE*EBW*E1003*1"},
                          "status": {"value": "planned"}}},
                        {"aegiElectricChargingPointStatus": {
                          "reference": {"targetClass": "RefillPoint"},
                          "status": {"value": "available"}}},
                        {"aegiElectricChargingPointStatus": {
                          "reference": {"targetClass": "RefillPoint", "idG": "internal-4711"},
                          "lastUpdated": "2026-10-02T09:00:00",
                          "status": {"value": "unknown"}}}
                      ]
                    }]
                  }]
                }
              }],
              "exchangeInformation": {
                "exchangeContext": {"codedExchangeProtocol": {"value": "snapshotPull"}},
                "dynamicInformation": {"exchangeStatus": {"value": "online"},
                                       "messageGenerationTimestamp": "2026-10-02T10:00:00+02:00"}
              }
            }}
            """;

    static final String DELTA = """
            {"messageContainer": {
              "payload": [{"aegiEnergyInfrastructureStatusPublication": {
                "publicationTime": "2026-10-02T10:01:00+02:00",
                "energyInfrastructureSiteStatus": [{"energyInfrastructureStationStatus": [{"refillPointStatus": [
                  {"aegiElectricChargingPointStatus": {
                    "reference": {"idG": "DE*EBW*E1001*1"},
                    "lastUpdated": "2026-10-02T10:00:40+02:00",
                    "status": {"value": "occupied"}}}
                ]}]}]}}],
              "exchangeInformation": {"exchangeContext": {"codedExchangeProtocol": {"value": "deltaPull"}}}
            }}
            """;

    static AfirStatusParser.Package parse(String json) throws IOException {
        return AfirStatusParser.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("reads every charge point status of a snapshot, normalized by EVSE-ID")
    void readsSnapshot() throws IOException {
        AfirStatusParser.Package snapshot = parse(SNAPSHOT);

        assertThat(snapshot.delta()).isFalse();
        assertThat(snapshot.chargePoints()).containsOnlyKeys(
                "DEEBWE10011", "DEEBWE10012", "DEEBWE10013", "DEEBWE1002", "INTERNAL4711");
        assertThat(snapshot.chargePoints().get("DEEBWE10011"))
                .isEqualTo(new AfirStatusParser.Reported(LiveAvailability.AVAILABLE, Instant.parse("2026-10-02T07:58:00Z")));
        assertThat(snapshot.chargePoints().get("DEEBWE10012").status()).isEqualTo(LiveAvailability.OCCUPIED);
        // The operator's "technical defect" outranks a refill point that merely reads as unoccupied.
        assertThat(snapshot.chargePoints().get("DEEBWE10013").status()).isEqualTo(LiveAvailability.OUT_OF_ORDER);
        // A bare object instead of an array of one, and no lastUpdated: falls back to the publication time.
        assertThat(snapshot.chargePoints().get("DEEBWE1002"))
                .isEqualTo(new AfirStatusParser.Reported(LiveAvailability.OUT_OF_ORDER, Instant.parse("2026-10-02T08:00:00Z")));
        // A local time without offset is German time.
        assertThat(snapshot.chargePoints().get("INTERNAL4711"))
                .isEqualTo(new AfirStatusParser.Reported(LiveAvailability.UNKNOWN, Instant.parse("2026-10-02T07:00:00Z")));
        // planned, and the status without an id.
        assertThat(snapshot.ignored()).isEqualTo(2);
        assertThat(snapshot.evseShaped()).isEqualTo(4);
    }

    @Test
    @DisplayName("recognises a delta by its exchange protocol")
    void readsDelta() throws IOException {
        AfirStatusParser.Package delta = parse(DELTA);

        assertThat(delta.delta()).isTrue();
        assertThat(delta.chargePoints()).containsOnlyKeys("DEEBWE10011");
        assertThat(delta.chargePoints().get("DEEBWE10011").status()).isEqualTo(LiveAvailability.OCCUPIED);
    }

    @Test
    @DisplayName("a package that does not say what it is counts as a delta, so it can never wipe a feed")
    void unlabelledPackageIsDelta() throws IOException {
        AfirStatusParser.Package unlabelled = parse("""
                {"messageContainer": {"payload": [{"aegiEnergyInfrastructureStatusPublication": {
                  "energyInfrastructureSiteStatus": {"energyInfrastructureStationStatus": {"refillPointStatus":
                    {"aegiElectricChargingPointStatus": {"reference": {"idG": "DE*ABC*E1"}, "status": "available"}}}}}}]}}
                """);

        assertThat(unlabelled.delta()).isTrue();
        // A bare string where the schema wants {"value": …} is read as well.
        assertThat(unlabelled.chargePoints().get("DEABCE1").status()).isEqualTo(LiveAvailability.AVAILABLE);
    }

    @Test
    @DisplayName("an empty package is a valid snapshot of nothing")
    void readsEmptySnapshot() throws IOException {
        AfirStatusParser.Package empty = parse("""
                {"messageContainer": {"payload": [],
                  "exchangeInformation": {"exchangeContext": {"codedExchangeProtocol": {"value": "snapshotPull"}}}}}
                """);

        assertThat(empty.delta()).isFalse();
        assertThat(empty.chargePoints()).isEmpty();
    }

    @Test
    @DisplayName("reads several statuses under one key, and the newer of two mentions of one charge point")
    void readsStatusArrayAndKeepsNewerMention() throws IOException {
        AfirStatusParser.Package received = parse("""
                {"messageContainer": {
                  "payload": [{"aegiEnergyInfrastructureStatusPublication": {
                    "publicationTime": {"unexpected": "object"},
                    "aegiRefillPointStatus": [
                      {"reference": {"idG": "DE*ABC*E1"}, "lastUpdated": "2026-10-02T10:05:00+02:00", "status": {"value": "charging"}},
                      {"reference": {"idG": "DE*ABC*E1"}, "lastUpdated": "2026-10-02T10:00:00+02:00", "status": {"value": "available"}},
                      {"reference": {"idG": "DE*ABC*E2"}, "lastUpdated": "yesterday", "status": {"value": ["available"]}},
                      {"reference": {"idG": "DE*ABC*E3"}, "status": {"value": []}},
                      {"reference": {"idG": "  "}, "status": {"value": "available"}}
                    ]}}],
                  "exchangeInformation": {"exchangeContext": {"codedExchangeProtocol": {"value": "deltaPush"}}}}}
                """);

        assertThat(received.delta()).isTrue();
        assertThat(received.chargePoints().get("DEABCE1"))
                .isEqualTo(new AfirStatusParser.Reported(LiveAvailability.OCCUPIED, Instant.parse("2026-10-02T08:05:00Z")));
        // An unreadable timestamp and no usable publication time: the status stands, without an age.
        assertThat(received.chargePoints().get("DEABCE2"))
                .isEqualTo(new AfirStatusParser.Reported(LiveAvailability.AVAILABLE, null));
        // An empty status array and a blank id are no statuses.
        assertThat(received.chargePoints()).doesNotContainKey("DEABCE3");
        assertThat(received.ignored()).isEqualTo(2);
    }

    @Test
    @DisplayName("the first publication time is the fallback, a protocol without a value changes nothing")
    void firstPublicationTimeAndEmptyProtocol() throws IOException {
        AfirStatusParser.Package received = parse("""
                {"messageContainer": {
                  "payload": [
                    {"aegiEnergyInfrastructureStatusPublication": {"publicationTime": "2026-10-02T10:00:00+02:00"}},
                    {"aegiEnergyInfrastructureStatusPublication": {"publicationTime": "2026-10-02T11:00:00+02:00",
                      "aegiElectricChargingPointStatus": {"reference": {"idG": "DE*ABC*E1"}, "status": {"value": "available"}}}}],
                  "exchangeInformation": {"exchangeContext": {"codedExchangeProtocol": {"extendedValueG": "custom"}}}}}
                """);

        assertThat(received.delta()).isTrue();
        assertThat(received.chargePoints().get("DEABCE1").observedAt()).isEqualTo(Instant.parse("2026-10-02T08:00:00Z"));
    }

    /**
     * The XML syntax ladenetz.de and ladebusiness deliver: enums as element text, the reference as attributes, and
     * a planned status nested inside a charge point that must not overwrite its current one.
     */
    static final String XML_SNAPSHOT = """
            \uFEFF<?xml version="1.0" encoding="UTF-8"?>
            <con:messageContainer xmlns:con="http://datex2.eu/schema/3/messageContainer"
                xmlns:ex="http://datex2.eu/schema/3/exchangeInformation"
                xmlns:com="http://datex2.eu/schema/3/common"
                xmlns:egi="http://datex2.eu/schema/3/energyInfrastructure"
                xmlns:f="http://datex2.eu/schema/3/facilities"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" modelBaseVersion="3">
              <con:payload xsi:type="egi:EnergyInfrastructureStatusPublication" lang="de" modelBaseVersion="3">
                <com:publicationTime>2026-10-02T22:00:00+02:00</com:publicationTime>
                <egi:energyInfrastructureSiteStatus>
                  <f:reference targetClass="egi:EnergyInfrastructureSite" id="SITE-1" version="1"/>
                  <egi:energyInfrastructureStationStatus>
                    <f:reference targetClass="egi:EnergyInfrastructureStation" id="STATION-1" version="1"/>
                    <egi:refillPointStatus xsi:type="egi:ElectricChargingPointStatus">
                      <f:reference targetClass="egi:ElectricChargingPoint" id="DE*LND*E0001*1" version="1"/>
                      <f:lastUpdated>2026-10-02T21:58:00+02:00</f:lastUpdated>
                      <egi:status>charging</egi:status>
                      <egi:plannedRefillPointStatus>
                        <egi:status>available</egi:status>
                        <egi:overallPeriod><com:overallStartTime>2026-10-02T23:00:00+02:00</com:overallStartTime></egi:overallPeriod>
                      </egi:plannedRefillPointStatus>
                    </egi:refillPointStatus>
                    <egi:refillPointStatus xsi:type="egi:ElectricChargingPointStatus">
                      <f:reference targetClass="egi:ElectricChargingPoint" id="DE*LND*E0001*2" version="1"/>
                      <f:operationStatus>technicalDefect</f:operationStatus>
                      <egi:status>available</egi:status>
                    </egi:refillPointStatus>
                    <egi:refillPointStatus xsi:type="egi:ElectricChargingPointStatus">
                      <f:reference targetClass="egi:ElectricChargingPoint" id="4711" version="1"/>
                      <egi:status>planned</egi:status>
                    </egi:refillPointStatus>
                  </egi:energyInfrastructureStationStatus>
                </egi:energyInfrastructureSiteStatus>
              </con:payload>
              <con:exchangeInformation modelBaseVersion="3">
                <ex:exchangeContext><ex:codedExchangeProtocol>snapshotPull</ex:codedExchangeProtocol></ex:exchangeContext>
              </con:exchangeInformation>
            </con:messageContainer>
            """;

    @Test
    @DisplayName("reads an XML package the same way, whichever prefixes it uses")
    void readsXmlSnapshot() throws IOException {
        AfirStatusParser.Package snapshot = parse(XML_SNAPSHOT);

        assertThat(snapshot.delta()).isFalse();
        assertThat(snapshot.chargePoints()).containsOnlyKeys("DELNDE00011", "DELNDE00012");
        // The nested planned "available" does not overwrite the current "charging".
        assertThat(snapshot.chargePoints().get("DELNDE00011"))
                .isEqualTo(new AfirStatusParser.Reported(LiveAvailability.OCCUPIED, Instant.parse("2026-10-02T19:58:00Z")));
        // Operation status outranks the refill point status; no lastUpdated falls back on the publication time.
        assertThat(snapshot.chargePoints().get("DELNDE00012"))
                .isEqualTo(new AfirStatusParser.Reported(LiveAvailability.OUT_OF_ORDER, Instant.parse("2026-10-02T20:00:00Z")));
        assertThat(snapshot.ignored()).isEqualTo(1);
        assertThat(snapshot.evseShaped()).isEqualTo(2);
        assertThat(snapshot.otherIdSamples()).isEmpty();
    }

    @Test
    @DisplayName("an XML delta without whitespace before it is recognised as a delta")
    void readsXmlDelta() throws IOException {
        AfirStatusParser.Package delta = parse("""
                <con:messageContainer xmlns:con="c" xmlns:ex="e" xmlns:egi="g" xmlns:f="f">
                  <egi:refillPointStatus><f:reference id="DE*LND*E0001*1"/><egi:status>available</egi:status></egi:refillPointStatus>
                  <egi:refillPointStatus><egi:status>available</egi:status></egi:refillPointStatus>
                  <ex:codedExchangeProtocol>deltaPull</ex:codedExchangeProtocol>
                </con:messageContainer>""");

        assertThat(delta.delta()).isTrue();
        assertThat(delta.chargePoints().get("DELNDE00011").status()).isEqualTo(LiveAvailability.AVAILABLE);
        assertThat(delta.ignored()).isEqualTo(1);
    }

    @Test
    @DisplayName("an XML package declaring external entities is refused, not resolved")
    void refusesExternalEntities() {
        assertThatThrownBy(() -> parse("""
                <?xml version="1.0"?>
                <!DOCTYPE con [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <con><refillPointStatus><reference id="&secret;"/><status>available</status></refillPointStatus></con>
                """)).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("broken XML is an unreadable package")
    void refusesBrokenXml() {
        assertThatThrownBy(() -> parse("<con><refillPointStatus>")).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("names a few ids that are not EVSE-shaped, as published, so the log shows the feed's id scheme")
    void samplesOtherIds() throws IOException {
        StringBuilder json = new StringBuilder("{\"messageContainer\": {\"payload\": [{\"aegiRefillPointStatus\": [");
        for (int i = 1; i <= 5; i++)
            json.append(i > 1 ? "," : "").append("{\"reference\": {\"idG\": \"wir-").append(i)
                    .append("\"}, \"status\": {\"value\": \"available\"}}");
        json.append(",{\"reference\": {\"idG\": \"wir-1\"}, \"status\": {\"value\": \"charging\"}}]}]}}");

        AfirStatusParser.Package received = parse(json.toString());

        assertThat(received.otherIdSamples()).containsExactly("wir-1", "wir-2", "wir-3");
        assertThat(parse(SNAPSHOT).otherIdSamples()).containsExactly("internal-4711");
    }

    @Test
    @DisplayName("an empty body is an empty delta, not an error")
    void readsEmptyBody() throws IOException {
        AfirStatusParser.Package empty = parse("   ");

        assertThat(empty.delta()).isTrue();
        assertThat(empty.chargePoints()).isEmpty();
    }

    @ParameterizedTest(name = "{0} / {1} -> {2}")
    @CsvSource(nullValues = "null", value = {
            "available,       null,                   AVAILABLE",
            "available,       inOperation,            AVAILABLE",
            "available,       limitedOperation,       AVAILABLE",
            "charging,        null,                   OCCUPIED",
            "occupied,        null,                   OCCUPIED",
            "reserved,        null,                   OCCUPIED",
            "blocked,         null,                   OCCUPIED",
            "faulted,         null,                   OUT_OF_ORDER",
            "inoperative,     null,                   OUT_OF_ORDER",
            "outOfOrder,      null,                   OUT_OF_ORDER",
            "unavailable,     null,                   OUT_OF_ORDER",
            "available,       notInOperation,         OUT_OF_ORDER",
            "charging,        notInOperationAbnormal, OUT_OF_ORDER",
            "null,            technicalDefect,        OUT_OF_ORDER",
            "unknown,         null,                   UNKNOWN",
            "planned,         null,                   null",
            "removed,         null,                   null",
            "outOfStock,      null,                   null",
            "extendedG,       null,                   null",
            "null,            null,                   null"})
    void mapsStatus(String status, String operationStatus, String expected) {
        assertThat(AfirStatusParser.toLiveAvailability(status, operationStatus)).isEqualTo(expected);
    }
}
