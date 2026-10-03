package de.joinside.evmap_service.sync.mobilithek;

import de.joinside.evmap_service.mobilithek.MobilithekBroker;
import de.joinside.evmap_service.sync.SourceStation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MobilithekSourceAdapterTests {
    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final MobilithekSyncProperties.Feed EWE = new MobilithekSyncProperties.Feed("ewe", "111", "EWE Go GmbH");
    private static final MobilithekSyncProperties.Feed ENBW = new MobilithekSyncProperties.Feed("enbw", "222", "EnBW");
    private static final MobilithekSyncProperties.Feed UNSUBSCRIBED = new MobilithekSyncProperties.Feed("tesla", " ", "Tesla");

    /** Plays back scripted answers per subscription and records every cursor it was asked with. */
    private static final class ScriptedBroker implements MobilithekBroker {
        private final Map<String, Deque<Object>> script = new HashMap<>();
        final List<String> requests = new ArrayList<>();

        ScriptedBroker answer(String subscriptionId, int status, String lastModified, String body) {
            InputStream stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
            script.computeIfAbsent(subscriptionId, key -> new ArrayDeque<>()).add(new Response(status, lastModified, stream));
            return this;
        }

        ScriptedBroker fail(String subscriptionId) {
            script.computeIfAbsent(subscriptionId, key -> new ArrayDeque<>()).add(new IOException("connection reset"));
            return this;
        }

        @Override
        public Response next(String subscriptionId, String ifModifiedSince) throws IOException {
            requests.add(subscriptionId + "@" + ifModifiedSince);
            Deque<Object> answers = script.get(subscriptionId);
            Object next = answers == null || answers.isEmpty()
                    ? new Response(304, null, InputStream.nullInputStream())
                    : answers.poll();
            if (next instanceof IOException failure) throw failure;
            return (Response) next;
        }
    }

    private static MobilithekSyncProperties properties(boolean enabled, int maxPackages, MobilithekSyncProperties.Feed... feeds) {
        return new MobilithekSyncProperties(enabled, "https://broker.invalid", "", "", "", Duration.ofSeconds(5),
                "BNetzA", "DE", maxPackages, List.of(feeds));
    }

    private static List<String> ids(Stream<SourceStation> stations) {
        try (stations) {
            return stations.map(SourceStation::sourceStationId).toList();
        }
    }

    @Test
    @DisplayName("reads every subscribed feed's snapshot in table order, one charge point once across feeds")
    void readsFeedsInOrder() {
        ScriptedBroker broker = new ScriptedBroker()
                .answer("111", 200, "Sat, 03 Oct 2026 03:03:10 GMT", AfirFixtures.EWE_JSON)
                .answer("222", 200, "Fri, 02 Oct 2026 22:00:31 GMT", AfirFixtures.ENBW_JSON);
        MobilithekSourceAdapter adapter = new MobilithekSourceAdapter(properties(true, 5, EWE, UNSUBSCRIBED, ENBW), broker, CLOCK);

        assertThat(adapter.source()).isEqualTo("MOBILITHEK");
        assertThat(adapter.enabled()).isTrue();
        assertThat(ids(adapter.fetchStations())).containsExactly("ewe/station-1", "ewe/station-2", "enbw/13529", "enbw/elli-3");
        // The cursor starts at the epoch and follows Last-Modified until the broker has nothing newer.
        assertThat(broker.requests).containsExactly(
                "111@" + MobilithekBroker.FROM_THE_START, "111@Sat, 03 Oct 2026 03:03:10 GMT",
                "222@" + MobilithekBroker.FROM_THE_START, "222@Fri, 02 Oct 2026 22:00:31 GMT");
    }

    @Test
    @DisplayName("of several buffered packages the newest wins, and the ceiling bounds the requests")
    void newestPackageWins() {
        ScriptedBroker broker = new ScriptedBroker()
                .answer("111", 200, "Fri, 02 Oct 2026 03:00:00 GMT", AfirFixtures.ENBW_JSON)
                .answer("111", 200, "Sat, 03 Oct 2026 03:00:00 GMT", AfirFixtures.EWE_JSON)
                .answer("111", 200, "Sun, 04 Oct 2026 03:00:00 GMT", AfirFixtures.LADENETZ_XML);

        assertThat(ids(new MobilithekSourceAdapter(properties(true, 2, EWE), broker, CLOCK).fetchStations()))
                .containsExactly("ewe/station-1", "ewe/station-2");
        assertThat(broker.requests).hasSize(2);
    }

    @Test
    @DisplayName("a package without Last-Modified is read once rather than asked for again")
    void stopsWithoutCursor() {
        ScriptedBroker broker = new ScriptedBroker().answer("111", 200, null, AfirFixtures.LADENETZ_XML);

        assertThat(ids(new MobilithekSourceAdapter(properties(true, 5, EWE), broker, CLOCK).fetchStations()))
                .containsExactly("ewe/DESTAS0187");
        assertThat(broker.requests).hasSize(1);
    }

    @Test
    @DisplayName("a feed the broker has nothing for, or does not deliver to us, is skipped and the others are read")
    void skipsFeedsWithoutData() {
        ScriptedBroker broker = new ScriptedBroker()
                .answer("111", 404, null, "")
                .answer("222", 200, null, AfirFixtures.ENBW_JSON);

        assertThat(ids(new MobilithekSourceAdapter(properties(true, 5, EWE, ENBW), broker, CLOCK).fetchStations()))
                .containsExactly("enbw/13529", "enbw/elli-3");
    }

    @Test
    @DisplayName("a feed that cannot be read ends the source's run instead of letting a platform stand in for it")
    void failsOnUnreadableFeed() {
        ScriptedBroker broker = new ScriptedBroker().fail("111").answer("222", 200, null, AfirFixtures.ENBW_JSON);
        MobilithekSourceAdapter adapter = new MobilithekSourceAdapter(properties(true, 5, EWE, ENBW), broker, CLOCK);

        assertThatThrownBy(() -> ids(adapter.fetchStations()))
                .isInstanceOf(UncheckedIOException.class).hasMessageContaining("ewe");
        assertThat(broker.requests).containsExactly("111@" + MobilithekBroker.FROM_THE_START);
    }

    @Test
    @DisplayName("a package that cannot be parsed fails the same way")
    void failsOnUnparseablePackage() {
        ScriptedBroker broker = new ScriptedBroker().answer("111", 200, null, "<broken><energyInfrastructureSite>");

        assertThatThrownBy(() -> ids(new MobilithekSourceAdapter(properties(true, 5, EWE), broker, CLOCK).fetchStations()))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    @DisplayName("a request that fails after a package was spooled fails the feed too")
    void failsAfterSpooling() {
        ScriptedBroker broker = new ScriptedBroker()
                .answer("111", 200, "Sat, 03 Oct 2026 03:03:10 GMT", AfirFixtures.EWE_JSON)
                .fail("111");

        assertThatThrownBy(() -> ids(new MobilithekSourceAdapter(properties(true, 5, EWE), broker, CLOCK).fetchStations()))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    @DisplayName("without a machine certificate the source delivers nothing instead of failing the run")
    void skipsWithoutCertificate() {
        MobilithekSourceAdapter adapter = new MobilithekSourceAdapter(properties(true, 5, EWE), null, CLOCK);
        assertThat(ids(adapter.fetchStations())).isEmpty();

        // The production constructor finds no certificate in these properties and loads none.
        assertThat(ids(new MobilithekSourceAdapter(properties(true, 5, EWE)).fetchStations())).isEmpty();
        assertThat(new MobilithekSourceAdapter(properties(false, 5, EWE)).enabled()).isFalse();
    }

    @Test
    @DisplayName("a certificate that cannot be loaded switches the source off with an error, not the container")
    void unreadableCertificate() {
        MobilithekSyncProperties broken = new MobilithekSyncProperties(true, "https://broker.invalid",
                "/does/not/exist.p12", "", "secret", Duration.ofSeconds(5), "BNetzA", "DE", 5, List.of(EWE));

        assertThat(ids(new MobilithekSourceAdapter(broken).fetchStations())).isEmpty();
        assertThat(broken.connection().toString()).doesNotContain("secret");
    }
}
