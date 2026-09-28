package de.joinside.evmap_service.sync.ocm;

import de.joinside.evmap_service.sync.AvailabilityStatus;
import de.joinside.evmap_service.sync.ConnectorTypes;
import de.joinside.evmap_service.sync.SourceAdapter;
import de.joinside.evmap_service.sync.SourceStation;
import de.joinside.evmap_service.sync.SyncStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Ingests international charging sites from Open Charge Map.
 * <p>
 * OCM requires an API key and publishes a fair usage policy that reserves the right to ban callers
 * making "excessive/indiscriminate" use of the API, so this adapter crawls a configured list of
 * countries with keyset paging, one throttled request at a time, and a hard page cap per country.
 * See ADR 0006.
 */
@Component
@ConditionalOnProperty(name = "evmap.sync.enabled", havingValue = "true")
@EnableConfigurationProperties(OpenChargeMapProperties.class)
class OpenChargeMapSourceAdapter implements SourceAdapter {
    private static final Logger log = LoggerFactory.getLogger(OpenChargeMapSourceAdapter.class);

    static final String SOURCE = "OCM";
    /** OCM asks callers to identify themselves beyond the API key. */
    private static final String USER_AGENT = "EVMap/1.0 (+https://evmap.joinside.de)";

    /**
     * Status types that describe something which is not installed, usable infrastructure today:
     * planned for a future date, decommissioned, or a listing OCM itself marked a duplicate.
     * Ingesting them would put chargers on the map that a driver cannot drive to — and a duplicate
     * listing additionally works against the geo-deduplication in the ingestion port.
     */
    private static final Set<Integer> EXCLUDED_STATUS_TYPES = Set.of(150, 200, 210);

    /**
     * Explicit status mapping, because {@code IsOperational} alone is not a usability signal:
     * "Temporarily Unavailable" (30) is flagged <em>operational</em> by OCM, and reading that flag
     * literally would advertise a station that is currently out of service as usable. Verified
     * against the live reference data on 2026-07-28. Unlisted ids fall back to the flag.
     */
    private static final Map<Integer, String> STATUS_BY_ID = Map.of(
            10, AvailabilityStatus.OPERATIONAL,   // Currently Available (Automated Status)
            20, AvailabilityStatus.OPERATIONAL,   // Currently In Use — works, merely occupied
            30, AvailabilityStatus.MAINTENANCE,   // Temporarily Unavailable
            50, AvailabilityStatus.OPERATIONAL,   // Operational
            75, AvailabilityStatus.OPERATIONAL,   // Partly Operational (Mixed)
            100, AvailabilityStatus.OUT_OF_SERVICE);
    /** OCM takes a date string for `modifiedsince`; ISO-8601 UTC is unambiguous on both sides. */
    private static final DateTimeFormatter MODIFIED_SINCE = DateTimeFormatter.ISO_INSTANT;

    private final OpenChargeMapProperties properties;
    private final RestClient restClient;
    private final SyncStateStore syncState;
    /**
     * Watermarks earned by countries whose crawl completed, held back until the ingestion confirms the
     * run committed. Written in {@link #commitProgress()}, never during the crawl.
     */
    private final Map<String, Instant> pendingWatermarks = new ConcurrentHashMap<>();

    @Autowired
    OpenChargeMapSourceAdapter(OpenChargeMapProperties properties, RestClient.Builder restClientBuilder,
                               SyncStateStore syncState) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());
        this.properties = properties;
        this.restClient = configure(properties, restClientBuilder.requestFactory(requestFactory)).build();
        this.syncState = syncState;
    }

    /** Takes a ready-made client so tests can supply one bound to a mock server. */
    OpenChargeMapSourceAdapter(OpenChargeMapProperties properties, RestClient restClient, SyncStateStore syncState) {
        this.properties = properties;
        this.restClient = restClient;
        this.syncState = syncState;
    }

    /**
     * Everything about the client except its transport: base URL, the API key OCM rejects requests
     * without, and the caller identity their documentation asks for. Kept separate from the request
     * factory so tests can apply the same wiring to a mock-bound transport and actually verify it.
     */
    static RestClient.Builder configure(OpenChargeMapProperties properties, RestClient.Builder builder) {
        return builder.baseUrl(properties.baseUrl())
                .defaultHeader("X-API-Key", properties.apiKey())
                .defaultHeader("User-Agent", USER_AGENT);
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public boolean enabled() {
        return properties.enabled();
    }

    @Override
    public Stream<SourceStation> fetchStations() {
        if (properties.apiKey().isBlank()) {
            // Not a hard failure: a deployment may legitimately run BNetzA only, and killing the whole
            // sync run over a missing optional key would take German coverage down with it.
            log.warn("OCM adapter skipped: no API key configured (evmap.sync.ocm.api-key / OCM_API_KEY). "
                    + "Register an application at https://openchargemap.org to obtain one");
            return Stream.empty();
        }

        // Anything a previous run earned but never got to commit is stale; this run re-earns it.
        pendingWatermarks.clear();
        // Taken before the first request, so a record modified mid-crawl falls inside the next
        // window rather than into the gap between "fetched" and "finished".
        Instant runStartedAt = Instant.now();

        Lookups lookups = fetchLookups();
        log.info("Fetching OCM sites for {} ({} connection types, {} operators known)",
                properties.countryCodes(), lookups.connectionTypes().size(), lookups.operators().size());
        // Lazy on purpose: countries are crawled as the ingestion consumes them, so a failure late
        // in the list does not first buy and then discard the whole crawl.
        return properties.countryCodes().stream()
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .filter(code -> !code.isEmpty())
                .flatMap(code -> stationsOf(code, lookups, runStartedAt));
    }

    /**
     * Advances the watermark of every country whose crawl ran to completion during this run.
     * <p>
     * Called by {@link de.joinside.evmap_service.sync.SyncJob} only after the ingestion committed
     * everything without a single failed record. Advancing earlier would mean a record fetched but
     * lost to a rollback is never asked for again — the one way an incremental sync silently loses
     * data instead of merely being late.
     */
    @Override
    public void commitProgress() {
        if (pendingWatermarks.isEmpty()) return;
        pendingWatermarks.forEach((countryCode, watermark) ->
                syncState.recordWatermark(SOURCE, countryCode, watermark));
        log.info("Advanced the OCM watermark to {} for {}",
                pendingWatermarks.values().iterator().next(), pendingWatermarks.keySet());
        pendingWatermarks.clear();
    }

    private Stream<SourceStation> stationsOf(String countryCode, Lookups lookups, Instant runStartedAt) {
        Instant modifiedSince = windowFor(countryCode, runStartedAt);
        // Set when a page comes back short, which is the only proof the country was crawled to the end.
        // Hitting the page cap deliberately leaves it false: a truncated crawl must not earn a watermark.
        AtomicBoolean complete = new AtomicBoolean(false);

        // Keyset paging: OCM has no page/offset, so each request asks for ids above the last one seen.
        // Sorting by id makes that a total order and keeps the crawl stable while the data changes.
        return Stream.iterate(page(countryCode, 0L, 1, modifiedSince, complete),
                        page -> !page.pois().isEmpty(),
                        page -> page.last()
                                ? Page.EMPTY
                                : page(countryCode, page.lastId(), page.number() + 1, modifiedSince, complete))
                .flatMap(page -> page.pois().stream())
                .map(poi -> toStation(poi, countryCode, lookups))
                .filter(Objects::nonNull)
                .onClose(() -> {
                    if (complete.get()) pendingWatermarks.put(countryCode, runStartedAt);
                });
    }

    /**
     * Decides whether this country is fetched incrementally, and from when.
     * <p>
     * A watermark older than {@code full-refresh-interval} triggers a full crawl instead: incremental
     * fetches never see upstream deletions, and only see edits OCM itself counts as a modification, so
     * the two copies drift apart until something sweeps the whole country again.
     *
     * @return the {@code modifiedsince} bound, or {@code null} for a full crawl
     */
    private Instant windowFor(String countryCode, Instant runStartedAt) {
        if (!properties.incremental()) return null;

        Optional<Instant> watermark = syncState.watermark(SOURCE, countryCode);
        if (watermark.isEmpty()) {
            log.info("Full OCM crawl for {}: no previous watermark", countryCode);
            return null;
        }
        Duration age = Duration.between(watermark.get(), runStartedAt);
        if (age.compareTo(properties.fullRefreshInterval()) >= 0) {
            log.info("Full OCM crawl for {}: watermark is {} old, past the {} refresh interval",
                    countryCode, age, properties.fullRefreshInterval());
            return null;
        }

        // Re-asking a little before the watermark absorbs clock skew against OCM and re-delivers
        // anything a previous run fetched but did not commit. Ingestion is an upsert, so the overlap
        // costs bandwidth and nothing else.
        Instant since = watermark.get().minus(properties.watermarkOverlap());
        log.info("Incremental OCM crawl for {}: sites modified since {}", countryCode, since);
        return since;
    }

    private Page page(String countryCode, long greaterThanId, int number, Instant modifiedSince,
                      AtomicBoolean complete) {
        if (number > properties.maxPagesPerCountry()) {
            log.warn("Stopped the OCM crawl for {} at the {}-page cap — raise evmap.sync.ocm.max-pages-per-country "
                    + "if this country genuinely has more sites", countryCode, properties.maxPagesPerCountry());
            return Page.EMPTY;
        }
        throttle(number);

        List<OcmResponses.Poi> pois = get(uri -> {
            uri = uri.path("/poi/")
                    .queryParam("output", "json")
                    .queryParam("client", properties.client())
                    .queryParam("countrycode", countryCode)
                    .queryParam("maxresults", properties.pageSize())
                    // compact/verbose strip the repeated reference-data objects and null fields; the ids
                    // that remain are resolved from the one-off /referencedata lookup.
                    .queryParam("compact", true)
                    .queryParam("verbose", false)
                    .queryParam("sortby", "id_asc")
                    .queryParam("greaterthanid", greaterThanId);
            if (properties.openDataOnly()) uri = uri.queryParam("opendata", true);
            if (modifiedSince != null) uri = uri.queryParam("modifiedsince", MODIFIED_SINCE.format(modifiedSince));
            return uri.build();
        }, OcmResponses.Poi[].class).map(List::of).orElseGet(List::of);

        boolean last = pois.size() < properties.pageSize();
        if (last) complete.set(true);
        log.debug("OCM page {} for {}: {} sites above id {}", number, countryCode, pois.size(), greaterThanId);
        return new Page(pois, number, last);
    }

    /**
     * Resolves the numeric ids that compact results carry. Fetched once per run rather than per page,
     * and degrading to empty maps rather than failing the run: an unresolved connector id becomes an
     * unmapped connector, while a failed run would cost the whole international dataset.
     */
    private Lookups fetchLookups() {
        Optional<OcmResponses.ReferenceData> reference;
        try {
            reference = get(uri -> uri.path("/referencedata/")
                    .queryParam("client", properties.client())
                    .build(), OcmResponses.ReferenceData.class);
        } catch (RuntimeException exception) {
            log.warn("OCM reference data unavailable ({}); connector types, operators and availability "
                    + "will be unresolved", exception.getMessage());
            return Lookups.empty();
        }
        return reference
                .map(data -> new Lookups(titlesOf(data.connectionTypes()), titlesOf(data.operators()),
                        operationalFlagsOf(data.statusTypes())))
                .orElseGet(Lookups::empty);
    }

    private static Map<Integer, String> titlesOf(List<OcmResponses.Titled> entries) {
        if (entries == null) return Map.of();
        Map<Integer, String> titles = new HashMap<>();
        for (OcmResponses.Titled entry : entries)
            if (entry.id() != null && entry.title() != null) titles.put(entry.id(), entry.title());
        return titles;
    }

    private <T> Optional<T> get(Function<UriBuilder, java.net.URI> uri, Class<T> type) {
        return Optional.ofNullable(restClient.get().uri(uri::apply).retrieve().body(type));
    }

    private void throttle(int pageNumber) {
        if (pageNumber <= 1 || properties.requestDelay().isZero() || properties.requestDelay().isNegative()) return;
        try {
            Thread.sleep(properties.requestDelay());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OCM crawl interrupted", exception);
        }
    }

    private SourceStation toStation(OcmResponses.Poi poi, String countryCode, Lookups lookups) {
        if (poi.id() == null || poi.addressInfo() == null) return null;
        if (poi.statusTypeId() != null && EXCLUDED_STATUS_TYPES.contains(poi.statusTypeId())) {
            log.debug("Skipped OCM site {} with status type {}", poi.id(), poi.statusTypeId());
            return null;
        }
        OcmResponses.AddressInfo address = poi.addressInfo();
        if (address.latitude() == null || address.longitude() == null) {
            log.debug("Skipped OCM site {} without coordinates", poi.id());
            return null;
        }

        String operator = poi.operatorId() == null ? null : lookups.operators().get(poi.operatorId());
        return new SourceStation(SOURCE,
                String.valueOf(poi.id()),
                // Community data leaves the site title blank often enough to need a fallback, and an
                // operator name locates a driver better than an empty label on the map.
                firstNonBlank(address.title(), operator),
                address.addressLine1(),
                address.town(),
                address.postcode(),
                // Taken from the request, not the payload: compact results carry only a numeric
                // CountryID, and the crawl already asked for exactly one country.
                countryCode,
                operator,
                address.latitude(),
                address.longitude(),
                availabilityOf(poi, lookups),
                lastUpdatedOf(poi),
                connectorsOf(poi, lookups));
    }

    /**
     * Maps OCM's status type onto the shared vocabulary.
     * <p>
     * Known ids are mapped explicitly (see {@link #STATUS_BY_ID}); anything OCM adds later falls back
     * to its {@code IsOperational} flag, which is coarser but still better than nothing. A status
     * with neither — id 0, "Unknown" — yields {@code null}: we do not know, which is not the same as
     * "it works".
     */
    private static String availabilityOf(OcmResponses.Poi poi, Lookups lookups) {
        if (poi.statusTypeId() == null) return null;
        String known = STATUS_BY_ID.get(poi.statusTypeId());
        if (known != null) return known;

        Boolean operational = lookups.operationalStatusTypes().get(poi.statusTypeId());
        if (operational == null) return null;
        return operational ? AvailabilityStatus.OPERATIONAL : AvailabilityStatus.OUT_OF_SERVICE;
    }

    /** Newest of the timestamps OCM offers, so the merge's "most recent source wins" rule is fair. */
    private static Instant lastUpdatedOf(OcmResponses.Poi poi) {
        return Stream.of(poi.dateLastStatusUpdate(), poi.dateLastVerified(), poi.dateCreated())
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElseGet(Instant::now);
    }

    private static List<SourceStation.SourceConnector> connectorsOf(OcmResponses.Poi poi, Lookups lookups) {
        if (poi.connections() == null) return List.of();
        List<SourceStation.SourceConnector> connectors = new ArrayList<>();
        for (OcmResponses.Connection connection : poi.connections()) {
            // A connection without a type, or with one outside the closed vocabulary, is not a
            // connector the app could filter on and is left out.
            String type = connection.connectionTypeId() == null ? null
                    : ConnectorTypes.normalize(lookups.connectionTypes().get(connection.connectionTypeId()));
            if (type != null) {
                int quantity = connection.quantity() == null || connection.quantity() < 1 ? 1 : connection.quantity();
                connectors.add(new SourceStation.SourceConnector(type, connection.powerKw(), quantity));
            }
        }
        return connectors;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates)
            if (candidate != null && !candidate.isBlank()) return candidate;
        return null;
    }

    private static Map<Integer, Boolean> operationalFlagsOf(List<OcmResponses.StatusType> entries) {
        if (entries == null) return Map.of();
        Map<Integer, Boolean> flags = new HashMap<>();
        for (OcmResponses.StatusType entry : entries)
            if (entry.id() != null && entry.isOperational() != null) flags.put(entry.id(), entry.isOperational());
        return flags;
    }

    /** Reference data resolved once per run and shared by every page. */
    private record Lookups(Map<Integer, String> connectionTypes, Map<Integer, String> operators,
                           Map<Integer, Boolean> operationalStatusTypes) {
        static Lookups empty() {
            return new Lookups(Map.of(), Map.of(), Map.of());
        }
    }

    /**
     * One page of results plus the state the next request needs.
     *
     * @param last a short page means the country is exhausted, so the crawl stops without spending a
     *             request on proving the next page is empty
     */
    private record Page(List<OcmResponses.Poi> pois, int number, boolean last) {
        private static final Page EMPTY = new Page(List.of(), 0, true);

        long lastId() {
            return pois.getLast().id();
        }
    }
}
