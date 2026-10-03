package de.joinside.evmap_service.availability.mobilithek;

import de.joinside.evmap_service.availability.Attribution;
import de.joinside.evmap_service.availability.AvailabilityProvider;
import de.joinside.evmap_service.availability.ChargePointAvailability;
import de.joinside.evmap_service.availability.GeoBounds;
import de.joinside.evmap_service.availability.Observations;
import de.joinside.evmap_service.logging.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Live availability for Germany from the Mobilithek, the national access point: one DATEX II v3 feed per charge
 * point operator, each brokered as its own subscription and pulled with the organisation's machine certificate.
 * <p>
 * The first provider filled in the background rather than on request, because the feeds are deltas: a delta is
 * only meaningful applied to the snapshot before it, so each feed has to be read continuously from its last full
 * package on, whether anyone is looking at a German station or not (ADR 0015, "Mobilithek survey", point 1).
 * {@link #poll()} does that once per {@code poll-interval}; {@link #fetch(GeoBounds)} only hands out the result.
 * <p>
 * Per feed, a round follows the broker's cursor: request with the previous {@code Last-Modified} — the epoch on
 * the first request, which returns the last full package — apply what comes back, repeat until 304. A snapshot
 * replaces what the feed said before, a delta updates it, 204 means the broker holds nothing and empties it. 403
 * and 404 leave the feed alone for {@code backoff}: the subscription is gone or not approved yet, or the
 * operator's access quota is exhausted, and hammering it would only use up the next one. A feed that has not
 * answered for {@code stale-after} reads as unknown; nothing is ever guessed.
 * <p>
 * Operators whose live feed names refill points by internal id rather than EVSE-ID — Wirelane, eRound and others —
 * are configured with their static feed as well; it is read once a day for nothing but the translation of those
 * ids into EVSE-IDs, so their charge points join exactly like everyone else's.
 * <p>
 * Every entry carries its feed's {@link Attribution}, because the operators license their data individually and
 * CC BY requires naming them (ADR 0015, L5 decision 6).
 * <p>
 * {@code max-age} is measured from the package that last confirmed a charge point, not from its {@code lastUpdated}:
 * that is the time of the last <em>change</em>, and a charge point that stays free for days is still reported
 * free in every snapshot (product owner, 2026-10-02; Wirelane carried a {@code lastUpdated} older than 72 h for
 * 4.034 of 4.081 charge points in a fresh snapshot). A feed that stopped is caught by the broker's validity window
 * and {@code stale-after}. The client still shows {@code lastUpdated}, so a long-unchanged status reads as such.
 */
@Component
@EnableConfigurationProperties(MobilithekProperties.class)
@ConditionalOnProperty(name = "evmap.availability.enabled", havingValue = "true", matchIfMissing = true)
public class MobilithekAvailabilityProvider implements AvailabilityProvider {
    private static final Logger log = LoggerFactory.getLogger(MobilithekAvailabilityProvider.class);

    static final String SOURCE = "Mobilithek";

    /** Used only where an entry carries no feed credit of its own, which this provider never emits. */
    private static final Attribution ATTRIBUTION = new Attribution(SOURCE,
            "AFIR-Daten der Ladepunktbetreiber", "https://mobilithek.info");

    /**
     * The cursor of a feed nobody has read yet. The broker answers it with its oldest package, which is by
     * definition the last full one (Schnittstellenbeschreibung §4.3, §4.8).
     */
    static final String FROM_THE_START = "Thu, 01 Jan 1970 00:00:00 GMT";

    /** What one feed has said so far. Touched only by the polling thread. */
    private static final class FeedState {
        final MobilithekProperties.Feed feed;
        final Attribution credit;
        final Map<String, Held> chargePoints = new HashMap<>();
        String cursor = FROM_THE_START;
        Instant lastSuccess;
        Instant backedOffUntil;
        /** Internal id → EVSE-ID from the operator's static feed; empty for operators that publish EVSE-IDs. */
        final Map<String, String> evseIdsByInternalId = new HashMap<>();
        String staticCursor = FROM_THE_START;
        /** When the static feed is next asked; {@code null} before the first time. */
        Instant staticDueAt;

        FeedState(MobilithekProperties.Feed feed) {
            this.feed = feed;
            this.credit = new Attribution(feed.publisher() + " via Mobilithek", feed.licence(), feed.url());
        }
    }

    /** One charge point's status and when a package last confirmed it. */
    private record Held(AfirStatusParser.Reported reported, Instant confirmedAt) {
    }

    /** The merged answer of the last round, replaced wholesale so a reader sees one round or the next. */
    private record Answer(Instant builtAt, List<ChargePointAvailability> entries) {
        static final Answer NONE = new Answer(null, List.of());
    }

    private final MobilithekProperties properties;
    private final MobilithekBroker broker;
    private final Clock clock;
    private final Set<String> countryCodes;
    private final Map<String, FeedState> feeds = new LinkedHashMap<>();
    private final AtomicReference<Answer> answer = new AtomicReference<>(Answer.NONE);

    @Autowired
    MobilithekAvailabilityProvider(MobilithekProperties properties) {
        this(properties, brokerFor(properties), Clock.systemUTC());
    }

    /** Test seam: a scripted broker and a controllable clock. {@code broker} is {@code null} when not configured. */
    MobilithekAvailabilityProvider(MobilithekProperties properties, MobilithekBroker broker, Clock clock) {
        this.properties = properties;
        this.broker = broker;
        this.clock = clock;
        this.countryCodes = properties.countryCodes().stream()
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .filter(code -> !code.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        for (MobilithekProperties.Feed feed : properties.subscribedFeeds())
            feeds.put(feed.subscriptionId(), new FeedState(feed));
        logConfiguration();
    }

    /**
     * Loads the machine certificate, or explains why there is none. A certificate that cannot be read switches
     * the provider off with an ERROR rather than failing the API: one country's live data is not worth the map.
     */
    private static MobilithekBroker brokerFor(MobilithekProperties properties) {
        if (!properties.enabled() || !properties.hasCertificate()) return null;
        try {
            return HttpsMobilithekBroker.create(properties, Clock.systemUTC());
        } catch (IOException | GeneralSecurityException e) {
            // The exception names the file and the failure, never the password.
            log.error("Mobilithek machine certificate could not be loaded{} — German live data from the Mobilithek is off",
                    properties.hasBase64Certificate() ? " from MOBILITHEK_KEYSTORE" : " from " + properties.keystorePath(), e);
            return null;
        }
    }

    private void logConfiguration() {
        if (!properties.enabled()) return;
        if (!properties.hasCertificate())
            log.info("Mobilithek is off: no machine certificate configured (MOBILITHEK_KEYSTORE or MOBILITHEK_KEYSTORE_PATH)");
        else if (feeds.isEmpty())
            log.info("Mobilithek is off: no feed has a subscription id yet");
        else if (broker != null)
            log.info("Mobilithek feeds: {} subscribed of {} configured", feeds.size(), properties.feeds().size());
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public Attribution attribution() {
        return ATTRIBUTION;
    }

    @Override
    public boolean enabled() {
        return properties.enabled() && broker != null && !feeds.isEmpty();
    }

    @Override
    public boolean covers(String countryCode) {
        return countryCodes.contains(countryCode);
    }

    /**
     * Every charge point any feed currently reports, whatever {@code bounds} says: the dynamic feeds carry no
     * coordinates, so the box cannot be applied, and {@code AvailabilityService} keeps only the identifiers it
     * asked for — as for France.
     */
    @Override
    public List<ChargePointAvailability> fetch(GeoBounds bounds) {
        Answer current = answer.get();
        if (current.builtAt() == null) return List.of();
        if (current.builtAt().plus(properties.staleAfter()).isBefore(clock.instant())) {
            log.debug("Last Mobilithek round finished {} — past stale-after, answering nothing", current.builtAt());
            return List.of();
        }
        return current.entries();
    }

    /** One round over every feed. Runs on the scheduler thread of the API container only. */
    @Scheduled(fixedDelayString = "${evmap.availability.mobilithek.poll-interval:PT60S}",
            initialDelayString = "${evmap.availability.mobilithek.initial-delay:PT5S}")
    void poll() {
        if (!enabled()) return;
        try (var _ = LogContext.scope(LogContext.SOURCE, SOURCE)) {
            for (FeedState feed : feeds.values()) {
                try {
                    if (feed.feed.hasStaticFeed()) refreshStaticIds(feed);
                    poll(feed);
                } catch (RuntimeException e) {
                    // A bug in one feed's handling must not stop the others from being read.
                    log.warn("Mobilithek feed {} failed unexpectedly — keeping its last state", feed.feed.publisher(), e);
                }
            }
            answer.set(merge(clock.instant()));
        }
    }

    private void poll(FeedState feed) {
        Instant now = clock.instant();
        if (feed.backedOffUntil != null && now.isBefore(feed.backedOffUntil)) return;

        for (int fetched = 0; fetched < properties.maxPackagesPerPoll(); fetched++) {
            try (MobilithekBroker.Response response = broker.next(feed.feed.subscriptionId(), feed.cursor)) {
                switch (response.status()) {
                    case 200 -> {
                        apply(feed, AfirStatusParser.parse(response.body()), now);
                        feed.lastSuccess = now;
                        if (response.lastModified() == null) {
                            // Without a cursor the next request would return the same package again.
                            log.warn("Mobilithek feed {} answered without Last-Modified — continuing next round",
                                    feed.feed.publisher());
                            return;
                        }
                        feed.cursor = response.lastModified();
                    }
                    case 304 -> {
                        feed.lastSuccess = now;
                        return;
                    }
                    case 204 -> {
                        // The broker holds no package: the operator's publication expired. Start over from
                        // whatever full package arrives next.
                        if (!feed.chargePoints.isEmpty())
                            log.info("Mobilithek feed {} has no data on the broker — clearing {} charge point(s)",
                                    feed.feed.publisher(), feed.chargePoints.size());
                        feed.chargePoints.clear();
                        feed.cursor = FROM_THE_START;
                        feed.lastSuccess = now;
                        return;
                    }
                    case 403, 404 -> {
                        feed.backedOffUntil = now.plus(properties.backoff());
                        log.warn("Mobilithek feed {} answered HTTP {} — subscription missing, not approved yet or "
                                        + "access quota exhausted; leaving it alone until {}",
                                feed.feed.publisher(), response.status(), feed.backedOffUntil);
                        return;
                    }
                    default -> {
                        log.warn("Mobilithek feed {} answered HTTP {} — keeping its last state",
                                feed.feed.publisher(), response.status());
                        return;
                    }
                }
            } catch (IOException e) {
                log.warn("Mobilithek feed {} could not be read — keeping its last state", feed.feed.publisher(), e);
                return;
            }
        }
        log.debug("Mobilithek feed {} has more than {} packages waiting — continuing next round",
                feed.feed.publisher(), properties.maxPackagesPerPoll());
    }

    /**
     * Reads the operator's static feed for its id translations, at most once per {@code static-refresh-interval}.
     * Translations are only ever added: an id that leaves the static feed keeps translating until restart, which is
     * harmless — it can only map a live status onto the EVSE-ID it was published for. Failures leave the existing
     * translations in place and are retried after {@code backoff}; the live feed is read either way.
     */
    private void refreshStaticIds(FeedState feed) {
        Instant now = clock.instant();
        if (feed.staticDueAt != null && now.isBefore(feed.staticDueAt)) return;
        feed.staticDueAt = now.plus(properties.backoff());
        int before = feed.evseIdsByInternalId.size();
        boolean more = true;
        for (int fetched = 0; more && fetched < properties.maxPackagesPerPoll(); fetched++) {
            try (MobilithekBroker.Response response = broker.next(feed.feed.staticSubscriptionId(), feed.staticCursor)) {
                if (response.status() == 200) {
                    feed.evseIdsByInternalId.putAll(AfirStaticIdParser.parse(response.body()));
                    // Without a cursor the next request would return the same package again.
                    more = response.lastModified() != null;
                    if (more) feed.staticCursor = response.lastModified();
                } else if (response.status() == 304 || response.status() == 204) {
                    more = false;
                } else {
                    log.warn("Mobilithek static feed of {} answered HTTP {} — keeping {} id translation(s), retrying at {}",
                            feed.feed.publisher(), response.status(), before, feed.staticDueAt);
                    return;
                }
            } catch (IOException e) {
                log.warn("Mobilithek static feed of {} could not be read — keeping {} id translation(s)",
                        feed.feed.publisher(), before, e);
                return;
            }
        }
        feed.staticDueAt = now.plus(properties.staticRefreshInterval());
        if (feed.evseIdsByInternalId.size() != before)
            log.info("Mobilithek feed {}: {} internal id(s) translated to EVSE-IDs from its static feed",
                    feed.feed.publisher(), feed.evseIdsByInternalId.size());
    }

    private static void apply(FeedState feed, AfirStatusParser.Package received, Instant now) {
        // A publication time from the future would keep a status alive past max-age; the arrival bounds it.
        Instant confirmedAt = received.publishedAt() != null && received.publishedAt().isBefore(now)
                ? received.publishedAt() : now;
        if (received.delta()) {
            // Packages arrive in delivery order, so the newer package wins even where its timestamp is older.
            received.chargePoints().forEach((evseId, reported) -> feed.chargePoints.put(evseId, new Held(reported, confirmedAt)));
            log.debug("Mobilithek feed {}: delta with {} charge point(s)", feed.feed.publisher(), received.chargePoints().size());
            return;
        }
        feed.chargePoints.clear();
        received.chargePoints().forEach((evseId, reported) -> feed.chargePoints.put(evseId, new Held(reported, confirmedAt)));
        // The samples show whether a feed whose ids are not EVSE-shaped uses internal ids (static feed needed) or
        // only another spelling of the EVSE-ID. Charge point ids are public infrastructure data, not personal.
        log.info("Mobilithek feed {}: snapshot with {} charge point(s), {} of them shaped like an EVSE-ID, {} ignored{}",
                feed.feed.publisher(), received.chargePoints().size(), received.evseShaped(), received.ignored(),
                received.otherIdSamples().isEmpty() ? "" : ", other ids e.g. " + received.otherIdSamples());
    }

    /**
     * All live feeds as one list. Where two feeds name one EVSE-ID — a roaming platform relaying an operator that
     * also publishes itself — the newer observation wins, the same rule {@code AvailabilityService} applies between
     * providers.
     */
    private Answer merge(Instant now) {
        Instant notBefore = now.minus(properties.maxAge());
        Map<String, ChargePointAvailability> merged = new HashMap<>();
        for (FeedState feed : feeds.values()) {
            if (feed.lastSuccess == null || feed.lastSuccess.plus(properties.staleAfter()).isBefore(now)) continue;
            feed.chargePoints.forEach((reportedId, held) -> {
                if (held.confirmedAt().isBefore(notBefore)) return;
                // Translated here rather than when a package arrives, so a static feed loaded later applies to the
                // state already held.
                String evseId = feed.evseIdsByInternalId.getOrDefault(reportedId, reportedId);
                AfirStatusParser.Reported reported = held.reported();
                merged.merge(evseId,
                        new ChargePointAvailability(evseId, reported.status(), reported.observedAt(), feed.credit),
                        (kept, candidate) -> Observations.newer(kept, candidate, ChargePointAvailability::observedAt));
            });
        }
        return new Answer(now, List.copyOf(merged.values()));
    }
}
