package de.joinside.evmap_service.availability.mobilithek;

import de.joinside.evmap_service.availability.Attribution;
import de.joinside.evmap_service.availability.ChargePointAvailability;
import de.joinside.evmap_service.availability.GeoBounds;
import de.joinside.evmap_service.availability.LiveAvailability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
        private final Map<String, Deque<Object>> script = new HashMap<>();
        final List<String> requests = new ArrayList<>();

        ScriptedBroker answer(String subscriptionId, int status, String lastModified, String body) {
            InputStream stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
            script.computeIfAbsent(subscriptionId, key -> new ArrayDeque<>()).add(new Response(status, lastModified, stream));
            return this;
        }

        /** The next request for {@code subscriptionId} throws {@code failure} instead of answering. */
        ScriptedBroker fail(String subscriptionId, Exception failure) {
            script.computeIfAbsent(subscriptionId, key -> new ArrayDeque<>()).add(failure);
            return this;
        }

        ScriptedBroker answer(String subscriptionId, int status) {
            return answer(subscriptionId, status, null, "");
        }

        @Override
        public Response next(String subscriptionId, String ifModifiedSince) throws IOException {
            requests.add(subscriptionId + "@" + ifModifiedSince);
            Deque<Object> answers = script.get(subscriptionId);
            // Anything not scripted is "nothing new", which is what an idle broker says.
            Object next = answers == null || answers.isEmpty()
                    ? new Response(304, null, InputStream.nullInputStream())
                    : answers.poll();
            if (next instanceof IOException failure) throw failure;
            if (next instanceof RuntimeException failure) throw failure;
            return (Response) next;
        }
    }

    private static MobilithekProperties properties(MobilithekProperties.Feed... feeds) {
        return properties(50, feeds);
    }

    private static MobilithekProperties properties(int maxPackagesPerPoll, MobilithekProperties.Feed... feeds) {
        return new MobilithekProperties(true, "https://broker.invalid/datexv3", "/run/secrets/mobilithek.p12", "", "",
                List.of("DE", " "), Duration.ofSeconds(60), maxPackagesPerPoll, Duration.ofHours(72),
                Duration.ofMinutes(10), Duration.ofHours(1), Duration.ofSeconds(30), List.of(feeds));
    }

    /** The shipped shape with a real certificate source, for the constructor that loads it. */
    private static MobilithekProperties withCertificate(boolean enabled, String path, String base64) {
        return new MobilithekProperties(enabled, "https://broker.invalid/datexv3", path, base64, "test-only-password",
                List.of("DE"), Duration.ofSeconds(60), 50, Duration.ofHours(72), Duration.ofMinutes(10),
                Duration.ofHours(1), Duration.ofSeconds(30), List.of(ENBW));
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
                .answer("111", 200, "Fri, 02 Oct 2026 08:00:00 GMT", AfirStatusParserTests.SNAPSHOT)
                .answer("111", 200, "Fri, 02 Oct 2026 08:01:00 GMT", AfirStatusParserTests.DELTA)
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
                .answer("111", 200, "T1", AfirStatusParserTests.SNAPSHOT)
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
                .answer("111", 200, "T1", AfirStatusParserTests.SNAPSHOT)
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
                .answer("111", 200, "T1", AfirStatusParserTests.SNAPSHOT);
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

    private static String publishedPackage(String publishedAt, String lastUpdated) {
        return """
                {"messageContainer": {
                  "payload": [{"aegiEnergyInfrastructureStatusPublication": {"publicationTime": "%s",
                    "energyInfrastructureSiteStatus": [{"energyInfrastructureStationStatus": [{"refillPointStatus": [
                      {"aegiElectricChargingPointStatus": {"reference": {"idG": "DE*ABC*E1"}, "lastUpdated": "%s",
                        "status": {"value": "available"}}}]}]}]}}],
                  "exchangeInformation": {"exchangeContext": {"codedExchangeProtocol": {"value": "snapshotPull"}}}}}
                """.formatted(publishedAt, lastUpdated);
    }

    @Test
    @DisplayName("a status unchanged for days still counts while a recent package confirms it, and shows its age")
    void ageIsMeasuredFromThePackage() {
        ScriptedBroker broker = new ScriptedBroker()
                .answer("111", 200, "T1", publishedPackage("2026-10-02T09:30:00+02:00", "2026-07-18T02:32:29+00:00"));
        MobilithekAvailabilityProvider provider =
                new MobilithekAvailabilityProvider(properties(ENBW), broker, new MovableClock());

        provider.poll();

        assertThat(provider.fetch(STUTTGART)).singleElement()
                .extracting(ChargePointAvailability::observedAt).isEqualTo(Instant.parse("2026-07-18T02:32:29Z"));
    }

    @Test
    @DisplayName("a status no package has confirmed for longer than max-age is not reported")
    void dropsUnconfirmedStatuses() {
        ScriptedBroker broker = new ScriptedBroker()
                .answer("111", 200, "T1", publishedPackage("2026-09-28T10:00:00+02:00", "2026-09-28T10:00:00+02:00"));
        MobilithekAvailabilityProvider provider =
                new MobilithekAvailabilityProvider(properties(ENBW), broker, new MovableClock());

        provider.poll();

        assertThat(provider.fetch(STUTTGART)).isEmpty();
    }

    @Test
    @DisplayName("a delta confirms only the charge points it names; the others age from their last package")
    void deltaConfirmsOnlyItsChargePoints() {
        ScriptedBroker broker = new ScriptedBroker()
                .answer("111", 200, "T1", publishedPackage("2026-09-29T10:30:00+02:00", "2026-09-29T10:30:00+02:00"))
                .answer("111", 200, "T2", """
                        {"messageContainer": {"payload": [{"aegiEnergyInfrastructureStatusPublication": {
                          "publicationTime": "2026-10-02T10:00:00+02:00",
                          "aegiElectricChargingPointStatus": {"reference": {"idG": "DE*ABC*E2"}, "status": {"value": "charging"}}}}],
                          "exchangeInformation": {"exchangeContext": {"codedExchangeProtocol": {"value": "deltaPull"}}}}}
                        """);
        MovableClock clock = new MovableClock();
        MobilithekAvailabilityProvider provider = new MobilithekAvailabilityProvider(properties(ENBW), broker, clock);

        provider.poll();
        assertThat(provider.fetch(STUTTGART)).hasSize(2);

        // The snapshot was 71 h 32 min old; an hour later it is past max-age, the delta from today is not.
        clock.advance(Duration.ofHours(1));
        provider.poll();

        assertThat(provider.fetch(STUTTGART)).extracting(ChargePointAvailability::evseId).containsExactly("DEABCE2");
    }

    @Test
    @DisplayName("a publication time in the future counts as the arrival, so it cannot keep a status alive")
    void futurePublicationTimeIsBounded() {
        ScriptedBroker broker = new ScriptedBroker()
                .answer("111", 200, "T1", publishedPackage("2030-01-01T00:00:00Z", "2026-10-02T09:00:00+02:00"));
        MovableClock clock = new MovableClock();
        MobilithekAvailabilityProvider provider = new MobilithekAvailabilityProvider(properties(ENBW), broker, clock);
        provider.poll();

        clock.advance(Duration.ofHours(73));
        provider.poll();

        assertThat(provider.fetch(STUTTGART)).isEmpty();
    }

    @Test
    @DisplayName("a broken package costs that round of that feed, not the state it had")
    void keepsStateOnUnreadablePackage() {
        ScriptedBroker broker = new ScriptedBroker()
                .answer("111", 200, "T1", AfirStatusParserTests.SNAPSHOT)
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

    @Test
    @DisplayName("loads the machine certificate itself and switches on with a subscribed feed")
    void loadsCertificate() {
        MobilithekAvailabilityProvider provider = new MobilithekAvailabilityProvider(
                withCertificate(true, "src/test/resources/mobilithek/test-machine-certificate.p12", ""));

        assertThat(provider.enabled()).isTrue();
        assertThat(provider.attribution().name()).isEqualTo("Mobilithek");
    }

    @Test
    @DisplayName("an unreadable certificate from either source, or a disabled provider, leaves it off")
    void offWhenCertificateUnusable() {
        assertThat(new MobilithekAvailabilityProvider(withCertificate(true, "", "bm90IGEga2V5c3RvcmU=")).enabled()).isFalse();
        assertThat(new MobilithekAvailabilityProvider(withCertificate(true, "/nonexistent.p12", "")).enabled()).isFalse();
        assertThat(new MobilithekAvailabilityProvider(
                withCertificate(false, "src/test/resources/mobilithek/test-machine-certificate.p12", "")).enabled()).isFalse();
    }

    @Test
    @DisplayName("when the polling stops, the last round's answer expires after stale-after")
    void answerExpiresWithoutPolling() {
        ScriptedBroker broker = new ScriptedBroker().answer("111", 200, "T1", AfirStatusParserTests.SNAPSHOT);
        MovableClock clock = new MovableClock();
        MobilithekAvailabilityProvider provider = new MobilithekAvailabilityProvider(properties(ENBW), broker, clock);
        provider.poll();

        clock.advance(Duration.ofMinutes(11));

        assertThat(provider.fetch(STUTTGART)).isEmpty();
    }

    @Test
    @DisplayName("a feed that fails — by I/O or by a bug — keeps its state and does not stop the others")
    void containsFailures() {
        ScriptedBroker broker = new ScriptedBroker()
                .answer("111", 200, "T1", AfirStatusParserTests.SNAPSHOT)
                .answer("222", 200, "T1", packageWith("snapshotPull", "DE*EWE*E1*1", "available", "2026-10-02T10:01:00+02:00"));
        MovableClock clock = new MovableClock();
        MobilithekAvailabilityProvider provider =
                new MobilithekAvailabilityProvider(properties(ENBW, EWE), broker, clock);
        provider.poll();

        broker.fail("111", new IOException("connection reset")).fail("222", new IllegalStateException("bug"));
        clock.advance(Duration.ofMinutes(1));
        provider.poll();

        assertThat(provider.fetch(STUTTGART)).hasSize(6);
    }

    @Test
    @DisplayName("a package without Last-Modified is applied once and the feed waits for the next round")
    void packageWithoutCursor() {
        ScriptedBroker broker = new ScriptedBroker().answer("111", 200, null, AfirStatusParserTests.SNAPSHOT);
        MobilithekAvailabilityProvider provider =
                new MobilithekAvailabilityProvider(properties(ENBW), broker, new MovableClock());

        provider.poll();

        assertThat(broker.requests).containsExactly("111@" + MobilithekAvailabilityProvider.FROM_THE_START);
        assertThat(provider.fetch(STUTTGART)).hasSize(5);
    }

    @Test
    @DisplayName("a feed further behind than max-packages-per-poll is caught up over the next rounds")
    void catchesUpOverRounds() {
        ScriptedBroker broker = new ScriptedBroker()
                .answer("111", 200, "T1", AfirStatusParserTests.SNAPSHOT)
                .answer("111", 200, "T2", AfirStatusParserTests.DELTA);
        MobilithekAvailabilityProvider provider =
                new MobilithekAvailabilityProvider(properties(1, ENBW), broker, new MovableClock());

        provider.poll();
        assertThat(broker.requests).hasSize(1);
        provider.poll();

        assertThat(broker.requests).containsExactly(
                "111@" + MobilithekAvailabilityProvider.FROM_THE_START, "111@T1");
        assertThat(provider.fetch(STUTTGART)).filteredOn(entry -> entry.evseId().equals("DEEBWE10011"))
                .extracting(ChargePointAvailability::status).containsExactly(LiveAvailability.OCCUPIED);
    }

    @Test
    @DisplayName("204 on a feed that never delivered is simply nothing")
    void emptyBufferOnNewFeed() {
        ScriptedBroker broker = new ScriptedBroker().answer("111", 204);
        MobilithekAvailabilityProvider provider =
                new MobilithekAvailabilityProvider(properties(ENBW), broker, new MovableClock());

        provider.poll();

        assertThat(provider.fetch(STUTTGART)).isEmpty();
        assertThat(provider.enabled()).isTrue();
    }
}
