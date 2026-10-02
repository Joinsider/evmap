package de.joinside.evmap_service.availability.mobilithek;

import de.joinside.evmap_service.availability.Attribution;
import de.joinside.evmap_service.availability.ChargePointAvailability;
import de.joinside.evmap_service.availability.GeoBounds;
import de.joinside.evmap_service.availability.LiveAvailability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MobilithekAvailabilityProviderTests {
    private static final GeoBounds STUTTGART = new GeoBounds(48.77, 9.17, 48.78, 9.19);
    private static final Instant NOW = Instant.parse("2026-10-02T08:02:00Z");
    private static final String CC_BY = "Creative Commons Namensnennung – 4.0 International (CC BY 4.0)";

    private static final MobilithekProperties.Feed ENBW =
            new MobilithekProperties.Feed("111", "EnBW AG", CC_BY, "https://mobilithek.info/offers/907575401287241728");
    private static final MobilithekProperties.Feed EWE =
            new MobilithekProperties.Feed("222", "EWE", "Creative Commons Zero (CC0 1.0)", "https://mobilithek.info/offers/1006184570533171200");
    private static final MobilithekProperties.Feed NOT_SUBSCRIBED =
            new MobilithekProperties.Feed("", "Tesla Germany GmbH", "CC0", "https://mobilithek.info/offers/953843379766972416");

    /** A clock the test moves forward, to cross stale-after and the back-off without sleeping. */
    private static final class MovableClock extends Clock {
        private Instant now = NOW;

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Plays back scripted answers per subscription and records every cursor it was asked with. */
    private static final class ScriptedBroker implements MobilithekBroker {
        private final Map<String, Deque<Response>> script = new HashMap<>();
        final List<String> requests = new ArrayList<>();

        ScriptedBroker answer(String subscriptionId, int status, String lastModified, String body) {
            InputStream stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
            script.computeIfAbsent(subscriptionId, key -> new ArrayDeque<>()).add(new Response(status, lastModified, stream));
            return this;
        }

        ScriptedBroker answer(String subscriptionId, int status) {
            return answer(subscriptionId, status, null, "");
        }

        @Override
        public Response next(String subscriptionId, String ifModifiedSince) {
            requests.add(subscriptionId + "@" + ifModifiedSince);
            Deque<Response> answers = script.get(subscriptionId);
            // Anything not scripted is "nothing new", which is what an idle broker says.
            return answers == null || answers.isEmpty()
                    ? new Response(304, null, InputStream.nullInputStream())
                    : answers.poll();
        }
    }

    private static MobilithekProperties properties(MobilithekProperties.Feed... feeds) {
        return new MobilithekProperties(true, "https://broker.invalid/datexv3", "/run/secrets/mobilithek.p12", "", "",
                List.of("DE"), Duration.ofSeconds(60), 50, Duration.ofHours(72), Duration.ofMinutes(10),
                Duration.ofHours(1), Duration.ofSeconds(30), List.of(feeds));
    }

    private static String packageWith(String protocol, String evseId, String status, String lastUpdated) {
        return """
                {"messageContainer": {
                  "payload": [{"aegiEnergyInfrastructureStatusPublication": {"energyInfrastructureSiteStatus": [{
                    "energyInfrastructureStationStatus": [{"refillPointStatus": [{"aegiElectricChargingPointStatus": {
                      "reference": {"idG": "%s"}, "lastUpdated": "%s", "status": {"value": "%s"}}}]}]}]}}],
                  "exchangeInformation": {"exchangeContext": {"codedExchangeProtocol": {"value": "%s"}}}}}
                """.formatted(evseId, lastUpdated, status, protocol);
    }

    @Test
    @DisplayName("reads a feed from its last full package on and follows the broker's cursor until 304")
    void followsCursor() {
        ScriptedBroker broker = new ScriptedBroker()
                .answer("111", 200, "Fri, 02 Oct 2026 08:00:00 GMT", AfirStatusJsonTests.SNAPSHOT)
                .answer("111", 200, "Fri, 02 Oct 2026 08:01:00 GMT", AfirStatusJsonTests.DELTA)
                .answer("111", 304);
        MobilithekAvailabilityProvider provider =
                new MobilithekAvailabilityProvider(properties(ENBW), broker, new MovableClock());

        provider.poll();

        assertThat(broker.requests).containsExactly(
                "111@" + MobilithekAvailabilityProvider.FROM_THE_START,
                "111@Fri, 02 Oct 2026 08:00:00 GMT",
                "111@Fri, 02 Oct 2026 08:01:00 GMT");
        List<ChargePointAvailability> live = provider.fetch(STUTTGART);
        // The delta moved DE*EBW*E1001*1 from available to occupied; the rest of the snapshot stands.
        assertThat(live).filteredOn(entry -> entry.evseId().equals("DEEBWE10011"))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.status()).isEqualTo(LiveAvailability.OCCUPIED);
                    assertThat(entry.observedAt()).isEqualTo(Instant.parse("2026-10-02T08:00:40Z"));
                });
        assertThat(live).extracting(ChargePointAvailability::evseId)
                .containsExactlyInAnyOrder("DEEBWE10011", "DEEBWE10012", "DEEBWE10013", "DEEBWE1002", "INTERNAL4711");
        // Credited to the operator under its own licence, not to the platform.
        assertThat(live).extracting(ChargePointAvailability::attribution).containsOnly(
                new Attribution("EnBW AG via Mobilithek", CC_BY, "https://mobilithek.info/offers/907575401287241728"));
    }

    @Test
    @DisplayName("a later snapshot replaces the feed, so a charge point it no longer names disappears")
    void snapshotReplaces() {
        ScriptedBroker broker = new ScriptedBroker()
                .answer("111", 200, "T1", AfirStatusJsonTests.SNAPSHOT)
                .answer("111", 200, "T2", packageWith("snapshotPull", "DE*EBW*E9999*1", "available", "2026-10-02T10:01:00+02:00"));
        MobilithekAvailabilityProvider provider =
                new MobilithekAvailabilityProvider(properties(ENBW), broker, new MovableClock());

        provider.poll();

        assertThat(provider.fetch(STUTTGART)).extracting(ChargePointAvailability::evseId).containsExactly("DEEBWE99991");
    }

    @Test
    @DisplayName("204 empties the feed and starts it over from the next full package")
    void emptyBufferClears() {
        ScriptedBroker broker = new ScriptedBroker()
                .answer("111", 200, "T1", AfirStatusJsonTests.SNAPSHOT)
                .answer("111", 304);
        MovableClock clock = new MovableClock();
        MobilithekAvailabilityProvider provider = new MobilithekAvailabilityProvider(properties(ENBW), broker, clock);
        provider.poll();
        assertThat(provider.fetch(STUTTGART)).isNotEmpty();

        broker.answer("111", 204);
        clock.advance(Duration.ofMinutes(1));
        provider.poll();

        assertThat(provider.fetch(STUTTGART)).isEmpty();
        clock.advance(Duration.ofMinutes(1));
        provider.poll();
        assertThat(broker.requests).last().isEqualTo("111@" + MobilithekAvailabilityProvider.FROM_THE_START);
    }

    @Test
    @DisplayName("404 leaves the feed alone for the back-off, then asks again; the other feeds carry on")
    void backsOffAfter404() {
        ScriptedBroker broker = new ScriptedBroker()
                .answer("111", 404)
                .answer("222", 200, "T1", packageWith("snapshotPull", "DE*EWE*E1*1", "available", "2026-10-02T10:01:00+02:00"));
        MovableClock clock = new MovableClock();
        MobilithekAvailabilityProvider provider =
                new MobilithekAvailabilityProvider(properties(ENBW, EWE), broker, clock);

        provider.poll();
        clock.advance(Duration.ofMinutes(30));
        provider.poll();

        assertThat(broker.requests).filteredOn(request -> request.startsWith("111@")).hasSize(1);
        assertThat(provider.fetch(STUTTGART)).extracting(ChargePointAvailability::evseId).containsExactly("DEEWEE11");

        clock.advance(Duration.ofMinutes(31));
        provider.poll();
        assertThat(broker.requests).filteredOn(request -> request.startsWith("111@")).hasSize(2);
    }

    @Test
    @DisplayName("a feed that stops answering reads as unknown after stale-after, not as its last state")
    void dropsStaleFeed() {
        ScriptedBroker broker = new ScriptedBroker()
                .answer("111", 200, "T1", AfirStatusJsonTests.SNAPSHOT);
        MovableClock clock = new MovableClock();
        MobilithekAvailabilityProvider provider = new MobilithekAvailabilityProvider(properties(ENBW), broker, clock);
        provider.poll();
        assertThat(provider.fetch(STUTTGART)).isNotEmpty();

        // Broker down for eleven minutes: every request fails with 503.
        for (int minute = 0; minute < 11; minute++) {
            clock.advance(Duration.ofMinutes(1));
            broker.answer("111", 503);
            provider.poll();
        }

        assertThat(provider.fetch(STUTTGART)).isEmpty();
    }

    @Test
    @DisplayName("when two feeds name one EVSE-ID, the newer observation wins")
    void newerFeedWins() {
        ScriptedBroker broker = new ScriptedBroker()
                .answer("111", 200, "T1", packageWith("snapshotPull", "DE*ABC*E1", "available", "2026-10-02T09:00:00+02:00"))
                .answer("222", 200, "T1", packageWith("snapshotPull", "DE*ABC*E1", "charging", "2026-10-02T10:00:00+02:00"));
        MobilithekAvailabilityProvider provider =
                new MobilithekAvailabilityProvider(properties(ENBW, EWE), broker, new MovableClock());

        provider.poll();

        assertThat(provider.fetch(STUTTGART)).singleElement().satisfies(entry -> {
            assertThat(entry.status()).isEqualTo(LiveAvailability.OCCUPIED);
            assertThat(entry.attribution().name()).isEqualTo("EWE via Mobilithek");
        });
    }

    @Test
    @DisplayName("statuses older than max-age are not reported: a feed that stopped is not a free charge point")
    void dropsOldStatuses() {
        ScriptedBroker broker = new ScriptedBroker()
                .answer("111", 200, "T1", packageWith("snapshotPull", "DE*ABC*E1", "available", "2026-09-28T10:00:00+02:00"));
        MobilithekAvailabilityProvider provider =
                new MobilithekAvailabilityProvider(properties(ENBW), broker, new MovableClock());

        provider.poll();

        assertThat(provider.fetch(STUTTGART)).isEmpty();
    }

    @Test
    @DisplayName("a broken package costs that round of that feed, not the state it had")
    void keepsStateOnUnreadablePackage() {
        ScriptedBroker broker = new ScriptedBroker()
                .answer("111", 200, "T1", AfirStatusJsonTests.SNAPSHOT)
                .answer("111", 304);
        MovableClock clock = new MovableClock();
        MobilithekAvailabilityProvider provider = new MobilithekAvailabilityProvider(properties(ENBW), broker, clock);
        provider.poll();

        broker.answer("111", 200, "T2", "{\"messageContainer\": {\"payload\": [");
        clock.advance(Duration.ofMinutes(1));
        provider.poll();

        assertThat(provider.fetch(STUTTGART)).hasSize(5);
        // The cursor did not move past the unreadable package; it is asked for again next round.
        clock.advance(Duration.ofMinutes(1));
        provider.poll();
        assertThat(broker.requests).last().isEqualTo("111@T1");
    }

    @Test
    @DisplayName("without a certificate or without a subscribed feed the provider is off and asks nothing")
    void offWithoutConfiguration() {
        ScriptedBroker broker = new ScriptedBroker();

        MobilithekAvailabilityProvider noBroker =
                new MobilithekAvailabilityProvider(properties(ENBW), null, new MovableClock());
        MobilithekAvailabilityProvider noFeed =
                new MobilithekAvailabilityProvider(properties(NOT_SUBSCRIBED), broker, new MovableClock());
        noBroker.poll();
        noFeed.poll();

        assertThat(noBroker.enabled()).isFalse();
        assertThat(noFeed.enabled()).isFalse();
        assertThat(broker.requests).isEmpty();
        assertThat(noFeed.fetch(STUTTGART)).isEmpty();
    }

    @Test
    @DisplayName("claims Germany only, and answers nothing before the first round")
    void scope() {
        MobilithekAvailabilityProvider provider =
                new MobilithekAvailabilityProvider(properties(ENBW), new ScriptedBroker(), new MovableClock());

        assertThat(provider.covers("DE")).isTrue();
        assertThat(provider.covers("FR")).isFalse();
        assertThat(provider.fetch(STUTTGART)).isEmpty();
        assertThat(provider.source()).isEqualTo("Mobilithek");
    }
}
