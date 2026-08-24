package de.joinside.evmap_service.api.station;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire format between this endpoint and the iOS client.
 * <p>
 * Nothing else covers this seam: the controller tests assert status codes and the Swift tests decode
 * a payload written by hand, so a serialization change — a timestamp becoming an epoch number, a
 * property being renamed — would pass both suites and break the app at runtime. The assertions below
 * mirror `StationLiveAvailability`'s `CodingKeys` and the `.iso8601` date strategy in
 * `JSONDecoder.evmap`.
 */
class AvailabilityPayloadTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            // Mirrors application.yaml, whose non_null inclusion is why the Swift side decodes every
            // nullable field with decodeIfPresent.
            .withPropertyValues("spring.jackson.default-property-inclusion=non_null");

    private static final AvailabilityController.StationAvailabilityResponse SAMPLE =
            new AvailabilityController.StationAvailabilityResponse(
                    UUID.fromString("6c8b4e1e-6c2c-4e9a-9b77-9f0a2e7b1c31"), "AVAILABLE",
                    2, 1, 0, 1, Instant.parse("2026-08-24T02:01:16Z"),
                    List.of(new AvailabilityController.ChargePointResponse(
                            UUID.fromString("1b0f0f5a-1111-4111-8111-111111111111"),
                            "DE*EBW*E912316*1", "AVAILABLE", Instant.parse("2026-08-24T02:01:16Z"))));

    @Test
    @DisplayName("serializes timestamps as ISO-8601, which is what the client decodes")
    void writesIso8601Timestamps() {
        runner.run(context -> {
            String json = context.getBean(ObjectMapper.class).writeValueAsString(SAMPLE);
            // An epoch number here would decode as a date far in the past on the client and get the
            // status hidden as stale, silently, with no error anywhere.
            assertThat(json).contains("\"observedAt\":\"2026-08-24T02:01:16Z\"");
        });
    }

    @Test
    @DisplayName("names the properties the Swift CodingKeys expect")
    void usesTheAgreedPropertyNames() {
        runner.run(context -> {
            String json = context.getBean(ObjectMapper.class).writeValueAsString(SAMPLE);
            assertThat(json).contains("\"stationId\":\"6c8b4e1e-6c2c-4e9a-9b77-9f0a2e7b1c31\"")
                    .contains("\"status\":\"AVAILABLE\"")
                    .contains("\"available\":2")
                    .contains("\"occupied\":1")
                    .contains("\"outOfOrder\":0")
                    .contains("\"unknown\":1")
                    .contains("\"chargePoints\":[")
                    // Published spelling, not the normalized comparison form: the client shows this
                    // so it can be read off the physical post.
                    .contains("\"evseId\":\"DE*EBW*E912316*1\"");
        });
    }

    @Test
    @DisplayName("omits a missing timestamp rather than writing null")
    void omitsNullTimestamp() {
        runner.run(context -> {
            var withoutObservation = new AvailabilityController.StationAvailabilityResponse(
                    UUID.randomUUID(), "UNKNOWN", 0, 0, 0, 4, null, List.of());
            String json = context.getBean(ObjectMapper.class).writeValueAsString(withoutObservation);

            assertThat(json).doesNotContain("observedAt");
            // The client's decodeIfPresent depends on exactly this: the key is absent, not null.
            assertThat(json).contains("\"status\":\"UNKNOWN\"");
        });
    }
}
