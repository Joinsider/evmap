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

class AfirStatusJsonTests {

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

    static AfirStatusJson.Package parse(String json) throws IOException {
        return AfirStatusJson.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("reads every charge point status of a snapshot, normalized by EVSE-ID")
    void readsSnapshot() throws IOException {
        AfirStatusJson.Package snapshot = parse(SNAPSHOT);

        assertThat(snapshot.delta()).isFalse();
        assertThat(snapshot.chargePoints()).containsOnlyKeys(
                "DEEBWE10011", "DEEBWE10012", "DEEBWE10013", "DEEBWE1002", "INTERNAL4711");
        assertThat(snapshot.chargePoints().get("DEEBWE10011"))
                .isEqualTo(new AfirStatusJson.Reported(LiveAvailability.AVAILABLE, Instant.parse("2026-10-02T07:58:00Z")));
        assertThat(snapshot.chargePoints().get("DEEBWE10012").status()).isEqualTo(LiveAvailability.OCCUPIED);
        // The operator's "technical defect" outranks a refill point that merely reads as unoccupied.
        assertThat(snapshot.chargePoints().get("DEEBWE10013").status()).isEqualTo(LiveAvailability.OUT_OF_ORDER);
        // A bare object instead of an array of one, and no lastUpdated: falls back to the publication time.
        assertThat(snapshot.chargePoints().get("DEEBWE1002"))
                .isEqualTo(new AfirStatusJson.Reported(LiveAvailability.OUT_OF_ORDER, Instant.parse("2026-10-02T08:00:00Z")));
        // A local time without offset is German time.
        assertThat(snapshot.chargePoints().get("INTERNAL4711"))
                .isEqualTo(new AfirStatusJson.Reported(LiveAvailability.UNKNOWN, Instant.parse("2026-10-02T07:00:00Z")));
        // planned, and the status without an id.
        assertThat(snapshot.ignored()).isEqualTo(2);
        assertThat(snapshot.evseShaped()).isEqualTo(4);
    }

    @Test
    @DisplayName("recognises a delta by its exchange protocol")
    void readsDelta() throws IOException {
        AfirStatusJson.Package delta = parse(DELTA);

        assertThat(delta.delta()).isTrue();
        assertThat(delta.chargePoints()).containsOnlyKeys("DEEBWE10011");
        assertThat(delta.chargePoints().get("DEEBWE10011").status()).isEqualTo(LiveAvailability.OCCUPIED);
    }

    @Test
    @DisplayName("a package that does not say what it is counts as a delta, so it can never wipe a feed")
    void unlabelledPackageIsDelta() throws IOException {
        AfirStatusJson.Package unlabelled = parse("""
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
        AfirStatusJson.Package empty = parse("""
                {"messageContainer": {"payload": [],
                  "exchangeInformation": {"exchangeContext": {"codedExchangeProtocol": {"value": "snapshotPull"}}}}}
                """);

        assertThat(empty.delta()).isFalse();
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
        assertThat(AfirStatusJson.toLiveAvailability(status, operationStatus)).isEqualTo(expected);
    }
}
