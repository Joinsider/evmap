package de.joinside.evmap_service.pricing.mobidata;

import de.joinside.evmap_service.availability.Attribution;
import de.joinside.evmap_service.availability.GeoBounds;
import de.joinside.evmap_service.pricing.AdHocPrice;
import de.joinside.evmap_service.pricing.ChargePointPrice;
import de.joinside.evmap_service.pricing.PriceProvider;
import de.joinside.evmap_service.sync.EvseIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Ad-hoc prices from MobiData BW's Open ChargePoint DataBase: the OCPI tariffs of the German AFIR feeds
 * (ecoMovement, chargecloud, EnBW), joined to charge points by their EVSE-ID.
 * <p>
 * Two reads: the whole tariff list (about a thousand, refreshed every {@code tariffTtl}) and, per area, the
 * locations whose connectors name a tariff. Only the second depends on where the user looks. How a tariff
 * becomes a gross price, or none, is {@link OcpiTariffs}'s job.
 */
@Component
@EnableConfigurationProperties(MobiDataPricingProperties.class)
@ConditionalOnProperty(name = "evmap.pricing.enabled", havingValue = "true", matchIfMissing = true)
public class MobiDataBwPriceProvider implements PriceProvider {
    private static final Logger log = LoggerFactory.getLogger(MobiDataBwPriceProvider.class);

    static final String SOURCE = "MobiDataBW";
    private static final Attribution ATTRIBUTION = new Attribution("MobiData BW",
            "Datenlizenz Deutschland – Namensnennung – 2.0", "https://www.mobidata-bw.de");
    /** OCPDB's synthetic id for EVSEs derived from the BNetzA register; never an EVSE-ID (see ADR 0015). */
    private static final Pattern BNETZA_SYNTHETIC = Pattern.compile("^BNETZA\\*[^*]+\\*.+$", Pattern.CASE_INSENSITIVE);

    private final MobiDataPricingProperties properties;
    private final RestClient restClient;
    private final Set<String> countryCodes;
    private final VatBasisTable basisTable;
    private final Clock clock;

    private record Catalog(Instant loadedAt, Map<String, OcpiTariffs.Tariff> tariffs,
                           Map<String, OcpiTariffs.TimeUnit> timeUnits) {
    }

    private final AtomicReference<Catalog> catalog = new AtomicReference<>();
    /** Last unit seen per feed, so a change — OCPDB fixing its mapping — is reported once, loudly. */
    private final Map<String, OcpiTariffs.TimeUnit> lastUnits = new HashMap<>();

    @Autowired
    MobiDataBwPriceProvider(MobiDataPricingProperties properties, RestClient.Builder restClientBuilder) {
        this(properties, restClientBuilder.requestFactory(requestFactory(properties)).baseUrl(properties.baseUrl()).build(),
                Clock.systemUTC());
    }

    /** Test seam: a preconfigured client and a clock the test controls. */
    MobiDataBwPriceProvider(MobiDataPricingProperties properties, RestClient restClient, Clock clock) {
        this.properties = properties;
        this.restClient = restClient;
        this.clock = clock;
        this.countryCodes = properties.countryCodes().stream()
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .filter(code -> !code.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.basisTable = VatBasisTable.of(properties.vatBasis());
        reportDueChecks();
    }

    /** Names the table entries whose check is older than {@code recheckAfter}; they stay in force (ADR 0022). */
    private void reportDueChecks() {
        List<MobiDataPricingProperties.OperatorBasis> due =
                basisTable.dueForRecheck(LocalDate.now(clock), properties.recheckAfter());
        if (!due.isEmpty())
            log.warn("{} VAT basis entr(ies) checked more than {} ago, due for a new check against the price pages: {}",
                    due.size(), properties.recheckAfter(),
                    due.stream().map(entry -> entry.operator() + " (" + entry.checkedOn() + ")").toList());
    }

    private static SimpleClientHttpRequestFactory requestFactory(MobiDataPricingProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.timeout());
        factory.setReadTimeout(properties.timeout());
        return factory;
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

    @Override
    public List<ChargePointPrice> fetch(GeoBounds bounds) {
        Catalog current = catalog();
        if (current.tariffs().isEmpty()) return List.of();

        List<OcpiTariffs.Location> locations = new ArrayList<>();
        int offset = 0;
        boolean more = true;
        for (int page = 0; more && page < properties.maxPages(); page++) {
            OcpiTariffs.LocationPage response = locations(bounds, offset);
            List<OcpiTariffs.Location> items = response == null || response.items() == null ? List.of() : response.items();
            locations.addAll(items);
            offset += items.size();
            // An empty or failed page ends the paging, and so does reaching the reported total.
            more = !items.isEmpty() && (response.totalCount() == null || offset < response.totalCount());
        }
        // Contradictions first, so no price of a suspended operator leaves this area.
        for (OcpiTariffs.Location location : locations) checkTable(location, current);
        List<ChargePointPrice> prices = new ArrayList<>();
        Set<String> uncertain = new LinkedHashSet<>();
        for (OcpiTariffs.Location location : locations) collect(location, current, prices, uncertain);
        log.debug("MobiData BW priced {} charge point(s) for bounds {}; {} tariff(s) left unpriced as uncertain",
                prices.size(), bounds, uncertain.size());
        return prices;
    }

    /**
     * Suspends the table entry of the location's operator when one of its tariffs contradicts it: the hand check
     * is outdated, and until someone checks again, no price beats a price that is off by the VAT (ADR 0022, 5r).
     */
    private void checkTable(OcpiTariffs.Location location, Catalog current) {
        String operator = operatorOf(location);
        OcpiTariffs.TableBasis listed = basisTable.basisOf(operator);
        if (listed == OcpiTariffs.TableBasis.UNCHECKED) return;
        for (OcpiTariffs.Evse evse : evsesOf(location)) {
            OcpiTariffs.Tariff tariff = singleTariff(evse, current);
            if (OcpiTariffs.contradicts(tariff, listed, location.assumedVatRate()) && basisTable.suspend(operator)) {
                log.warn("MobiData BW tariff {} of {} contradicts its VAT basis entry {}; entry suspended until the "
                        + "price page is checked again", tariff.originalId(), operator, listed);
                return;
            }
        }
    }

    private void collect(OcpiTariffs.Location location, Catalog current, List<ChargePointPrice> into,
                         Set<String> uncertain) {
        String operator = operatorOf(location);
        for (OcpiTariffs.Evse evse : evsesOf(location)) {
            String evseId = identifierOf(evse);
            if (evseId == null) continue;
            AdHocPrice price = priceOf(evse, operator, location.assumedVatRate(), current, uncertain);
            if (price != null) into.add(new ChargePointPrice(evseId, price));
        }
    }

    private static String operatorOf(OcpiTariffs.Location location) {
        return location.operator() == null ? null : location.operator().name();
    }

    private static List<OcpiTariffs.Evse> evsesOf(OcpiTariffs.Location location) {
        if (location.chargingPool() == null) return List.of();
        return location.chargingPool().stream()
                .filter(chargeStation -> chargeStation != null && chargeStation.evses() != null)
                .flatMap(chargeStation -> chargeStation.evses().stream())
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * The EVSE's price: every connector must name the same single tariff and that tariff must read. Different
     * tariffs on one EVSE (a CCS and a CHAdeMO plug priced apart) cannot be one charge point's price.
     */
    private AdHocPrice priceOf(OcpiTariffs.Evse evse, String operator, BigDecimal assumedVatRate, Catalog current,
                               Set<String> uncertain) {
        OcpiTariffs.Tariff tariff = singleTariff(evse, current);
        if (tariff == null) return null;
        OcpiTariffs.TimeUnit unit = current.timeUnits().getOrDefault(tariff.source(), OcpiTariffs.TimeUnit.UNKNOWN);
        AdHocPrice price = OcpiTariffs.read(tariff, operator, unit, basisTable, assumedVatRate);
        if (price == null) uncertain.add(tariff.originalId());
        return price;
    }

    /** The one tariff all of the EVSE's connectors name, or {@code null}. */
    private static OcpiTariffs.Tariff singleTariff(OcpiTariffs.Evse evse, Catalog current) {
        if (evse.connectors() == null) return null;
        Set<String> tariffIds = new LinkedHashSet<>();
        for (OcpiTariffs.Connector connector : evse.connectors())
            if (connector != null && connector.tariffIds() != null) tariffIds.addAll(connector.tariffIds());
        return tariffIds.size() == 1 ? current.tariffs().get(tariffIds.iterator().next()) : null;
    }

    private static String identifierOf(OcpiTariffs.Evse evse) {
        String id = usable(evse.evseId());
        return id != null ? id : usable(evse.originalUid());
    }

    private static String usable(String candidate) {
        if (candidate == null || candidate.isBlank() || BNETZA_SYNTHETIC.matcher(candidate.trim()).matches()) return null;
        return EvseIds.normalize(candidate);
    }

    // --- tariff catalog -------------------------------------------------------------------------------

    private Catalog catalog() {
        Instant now = clock.instant();
        Catalog current = catalog.get();
        if (isFresh(current, now)) return current;
        synchronized (this) {
            current = catalog.get();
            if (isFresh(current, now)) return current;
            Catalog loaded = loadCatalog(now);
            // A failed refresh keeps the previous list rather than dropping every price for a while.
            Catalog kept = loaded.tariffs().isEmpty() && current != null
                    ? new Catalog(now, current.tariffs(), current.timeUnits())
                    : loaded;
            catalog.set(kept);
            return kept;
        }
    }

    private boolean isFresh(Catalog current, Instant now) {
        return current != null && current.loadedAt().plus(properties.tariffTtl()).isAfter(now);
    }

    private Catalog loadCatalog(Instant now) {
        Map<String, OcpiTariffs.Tariff> tariffs = new HashMap<>();
        int offset = 0;
        boolean more = true;
        for (int page = 0; more && page < properties.maxTariffPages(); page++) {
            OcpiTariffs.TariffPage response = tariffs(offset);
            List<OcpiTariffs.Tariff> items = response == null || response.items() == null ? List.of() : response.items();
            for (OcpiTariffs.Tariff tariff : items)
                if (tariff != null && tariff.originalId() != null) tariffs.put(tariff.originalId(), tariff);
            offset += items.size();
            more = !items.isEmpty() && response.nextOffset() != null;
        }
        Map<String, OcpiTariffs.TimeUnit> units = OcpiTariffs.detectTimeUnits(tariffs.values());
        reportUnits(units);
        log.info("Loaded {} MobiData BW tariff(s); time units per feed: {}", tariffs.size(), units);
        return new Catalog(now, Map.copyOf(tariffs), Map.copyOf(units));
    }

    /**
     * Warns when a feed's detected time unit changes. The expected change is OCPDB starting to convert DATEX
     * per-minute prices to OCPI's per-hour unit; the detection then divides by 60 on its own, and this line is
     * the trace that it happened (ADR 0022).
     */
    private void reportUnits(Map<String, OcpiTariffs.TimeUnit> units) {
        units.forEach((feed, unit) -> {
            OcpiTariffs.TimeUnit previous = lastUnits.put(feed, unit);
            if (previous != null && !Objects.equals(previous, unit))
                log.warn("Time unit of MobiData BW feed {} changed from {} to {}", feed, previous, unit);
        });
    }

    private OcpiTariffs.TariffPage tariffs(int offset) {
        try {
            return restClient.get()
                    .uri(uri -> uri.path("/tariffs")
                            .queryParam("limit", properties.tariffPageSize())
                            .queryParam("offset", offset)
                            .build())
                    .retrieve()
                    .body(OcpiTariffs.TariffPage.class);
        } catch (RuntimeException e) {
            log.warn("MobiData BW tariff request failed at offset {} — prices stay as they were", offset, e);
            return null;
        }
    }

    private OcpiTariffs.LocationPage locations(GeoBounds bounds, int offset) {
        try {
            return restClient.get()
                    .uri(uri -> uri.path("/locations")
                            .queryParam("lat_min", bounds.latMin())
                            .queryParam("lat_max", bounds.latMax())
                            .queryParam("lon_min", bounds.lonMin())
                            .queryParam("lon_max", bounds.lonMax())
                            .queryParam("limit", properties.pageSize())
                            .queryParam("offset", offset)
                            .build())
                    .retrieve()
                    .body(OcpiTariffs.LocationPage.class);
        } catch (RuntimeException e) {
            log.warn("MobiData BW location request failed for bounds {} at offset {} — prices unknown", bounds, offset, e);
            return null;
        }
    }
}
