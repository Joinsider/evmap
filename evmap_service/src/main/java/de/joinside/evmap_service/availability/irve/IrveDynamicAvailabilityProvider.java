package de.joinside.evmap_service.availability.irve;

import de.joinside.evmap_service.availability.Attribution;
import de.joinside.evmap_service.availability.AvailabilityProvider;
import de.joinside.evmap_service.availability.ChargePointAvailability;
import de.joinside.evmap_service.availability.GeoBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * Live availability for France, from transport.data.gouv.fr's national consolidation of every
 * operator's {@code schema-irve-dynamique} file (Licence Ouverte 2.0, no key, no registration).
 * <p>
 * Shaped differently from the other providers because the source is: there is no area query, only the
 * whole country as one CSV — and that CSV carries no coordinates, so a bounding box could not be
 * applied to it even after download. The provider therefore keeps the last download in memory and
 * answers every area with all of it; {@code AvailabilityService} keeps only the identifiers it asked
 * for, and since matching is by exact EVSE-ID, a larger answer is a cost and never a wrong result.
 * <p>
 * Downloads still follow usage, as ADR 0015 requires: nothing is fetched until someone looks at a
 * French station, and at most once per {@code refresh-interval} after that. Only the request that
 * finds the copy expired waits for the refresh; concurrent ones are answered from the previous copy,
 * so a slow proxy costs one slow request rather than a queue of them.
 */
@Component
@EnableConfigurationProperties(IrveDynamicProperties.class)
@ConditionalOnProperty(name = "evmap.availability.enabled", havingValue = "true", matchIfMissing = true)
public class IrveDynamicAvailabilityProvider implements AvailabilityProvider {
    private static final Logger log = LoggerFactory.getLogger(IrveDynamicAvailabilityProvider.class);

    static final String SOURCE = "IrveDynamique";

    private static final Attribution ATTRIBUTION = new Attribution("transport.data.gouv.fr",
            "Licence Ouverte 2.0", "https://transport.data.gouv.fr/resources/84098");

    /**
     * @param attemptedAt when the last download was tried, successful or not — what the refresh
     *                    interval is measured from, so a failing upstream is retried once per interval
     *                    rather than by every request
     * @param loadedAt    when {@code entries} were downloaded, {@code null} before the first success
     */
    private record Snapshot(Instant attemptedAt, Instant loadedAt, List<ChargePointAvailability> entries) {
        static final Snapshot EMPTY = new Snapshot(Instant.EPOCH, null, List.of());
    }

    private final IrveDynamicProperties properties;
    private final RestClient restClient;
    private final Clock clock;
    private final Set<String> countryCodes;
    private final ReentrantLock refreshing = new ReentrantLock();
    /** Replaced wholesale, never mutated: a reader sees either the old copy or the new one. */
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.EMPTY);

    @Autowired
    IrveDynamicAvailabilityProvider(IrveDynamicProperties properties, RestClient.Builder restClientBuilder) {
        this(properties, restClientBuilder.requestFactory(requestFactory(properties)).build(), Clock.systemUTC());
    }

    /** Test seam: a preconfigured client and a controllable clock. */
    IrveDynamicAvailabilityProvider(IrveDynamicProperties properties, RestClient restClient, Clock clock) {
        this.properties = properties;
        this.restClient = restClient;
        this.clock = clock;
        this.countryCodes = properties.countryCodes().stream()
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .filter(code -> !code.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The configured URL answers 302 twice — data.gouv.fr to transport.data.gouv.fr's proxy — which
     * {@code HttpURLConnection} follows itself because every hop is HTTPS.
     */
    private static SimpleClientHttpRequestFactory requestFactory(IrveDynamicProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());
        return requestFactory;
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
        return properties.enabled();
    }

    @Override
    public boolean covers(String countryCode) {
        return countryCodes.contains(countryCode);
    }

    /**
     * Every charge point in France with a recent status, whatever {@code bounds} says — see the class
     * documentation for why the box cannot be applied.
     */
    @Override
    public List<ChargePointAvailability> fetch(GeoBounds bounds) {
        Instant now = clock.instant();
        Snapshot current = snapshot.get();
        if (isDue(current, now) && refreshing.tryLock()) {
            try {
                current = snapshot.get();
                if (isDue(current, now)) {
                    current = refresh(current, now);
                    snapshot.set(current);
                }
            } finally {
                refreshing.unlock();
            }
        }
        if (current.loadedAt() == null) return List.of();
        if (current.loadedAt().plus(properties.staleAfter()).isBefore(now)) {
            log.debug("Last IRVE dynamique download is from {} — past stale-after, answering nothing", current.loadedAt());
            return List.of();
        }
        return current.entries();
    }

    private boolean isDue(Snapshot current, Instant now) {
        return !current.attemptedAt().plus(properties.refreshInterval()).isAfter(now);
    }

    /**
     * Downloads and parses the consolidation, or keeps the previous copy when that fails.
     * <p>
     * Never throws: the proxy is marked BETA by its own publisher, and the contract with
     * {@link AvailabilityProvider} is that a source being down degrades its area to unknown.
     */
    private Snapshot refresh(Snapshot previous, Instant now) {
        try {
            Instant notBefore = now.minus(properties.maxAge());
            List<ChargePointAvailability> entries = restClient.get()
                    .uri(URI.create(properties.csvUrl()))
                    .header(HttpHeaders.ACCEPT_ENCODING, "gzip")
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful())
                            throw new IOException("HTTP " + response.getStatusCode().value());
                        try (InputStream body = decoded(response.getBody(), response.getHeaders())) {
                            return IrveDynamicCsv.parse(new InputStreamReader(body, StandardCharsets.UTF_8), notBefore);
                        }
                    });
            log.info("IRVE dynamique refreshed: {} charge point(s) with a status newer than {}",
                    entries.size(), properties.maxAge());
            return new Snapshot(now, now, entries);
        } catch (RuntimeException e) {
            log.warn("IRVE dynamique download failed — keeping the copy from {}", previous.loadedAt(), e);
            return new Snapshot(now, previous.loadedAt(), previous.entries());
        }
    }

    /** The proxy compresses 9 MB to 1,6 MB on request; {@code HttpURLConnection} does not unpack it. */
    private static InputStream decoded(InputStream body, HttpHeaders headers) throws IOException {
        String encoding = headers.getFirst(HttpHeaders.CONTENT_ENCODING);
        return encoding != null && encoding.toLowerCase(Locale.ROOT).contains("gzip") ? new GZIPInputStream(body) : body;
    }
}
