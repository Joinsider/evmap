package de.joinside.evmap_service.availability.mobidata;

import de.joinside.evmap_service.availability.Attribution;
import de.joinside.evmap_service.availability.AvailabilityProvider;
import de.joinside.evmap_service.availability.ChargePointAvailability;
import de.joinside.evmap_service.availability.GeoBounds;
import de.joinside.evmap_service.sync.EvseIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Live availability from MobiData BW's Open ChargePoint DataBase (OCPI 3.0).
 * <p>
 * The first live source, chosen because it costs nothing to integrate: no API key, no registration,
 * no quota, an open licence (dl-de/by-2.0), and real AFIR dynamic data behind it — its
 * {@code datex2_ecomovement}, {@code datex2_chargecloud} and {@code datex2_tesla} feeds carried
 * 120.499 EVSEs with a non-static status when this was written.
 * <p>
 * Queried by bounding box on demand rather than polled in full. The alternative — keeping the whole
 * live picture in memory on a fixed delay — was rejected because OCPDB's {@code last_updated} tracks
 * the <em>static</em> description and not the status (records exist with a 2024 {@code last_updated}
 * and a status from minutes ago), so an incremental refresh would silently miss exactly the changes
 * it exists to catch, and a full refresh means ~240 requests per cycle regardless of whether anybody
 * is looking at that area.
 */
@Component
@EnableConfigurationProperties(MobiDataProperties.class)
@ConditionalOnProperty(name = "evmap.availability.enabled", havingValue = "true", matchIfMissing = true)
public class MobiDataBwAvailabilityProvider implements AvailabilityProvider {
    private static final Logger log = LoggerFactory.getLogger(MobiDataBwAvailabilityProvider.class);

    static final String SOURCE = "MobiDataBW";

    private static final Attribution ATTRIBUTION = new Attribution("MobiData BW",
            "Datenlizenz Deutschland – Namensnennung – 2.0", "https://www.mobidata-bw.de");

    /**
     * OCPDB's own synthesis for EVSEs it derived from the Bundesnetzagentur register, embedding the
     * {@code Ladeeinrichtungs-ID}. Recognized in order to be <em>skipped</em>.
     * <p>
     * It would reduce onto the {@code <Ladeeinrichtungs-ID>*<n>} key the BNetzA adapter writes, and
     * so is a workable second join path — worth 0,2 % of live EVSEs in a 20.000-record sample, 38 of
     * them. That does not pay for a second resolution rule against a different column, and letting
     * these through as if they were EVSE-IDs would instead put keys into the join that no operator
     * publishes and nothing in {@code master.charge_point.evse_id_normalized} can match. See ADR 0015.
     */
    private static final Pattern BNETZA_SYNTHETIC = Pattern.compile("^BNETZA\\*[^*]+\\*.+$", Pattern.CASE_INSENSITIVE);

    private final MobiDataProperties properties;
    private final RestClient restClient;
    private final Set<String> countryCodes;

    @Autowired
    MobiDataBwAvailabilityProvider(MobiDataProperties properties, RestClient.Builder restClientBuilder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());
        this.properties = properties;
        this.restClient = restClientBuilder.requestFactory(requestFactory).baseUrl(properties.baseUrl()).build();
        this.countryCodes = normalizedCountryCodes(properties);
    }

    /** Test seam: takes a preconfigured client rather than building one against the network. */
    MobiDataBwAvailabilityProvider(MobiDataProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
        this.countryCodes = normalizedCountryCodes(properties);
    }

    private static Set<String> normalizedCountryCodes(MobiDataProperties properties) {
        return properties.countryCodes().stream()
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .filter(code -> !code.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
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
    public List<ChargePointAvailability> fetch(GeoBounds bounds) {
        List<ChargePointAvailability> availability = new ArrayList<>();
        int offset = 0;
        for (int page = 0; offset >= 0 && page < properties.maxPages(); page++) {
            offset = readPage(bounds, offset, availability);
            if (offset >= 0 && page == properties.maxPages() - 1)
                log.debug("Stopped at the {}-page cap after {} locations for bounds {}",
                        properties.maxPages(), offset, bounds);
        }
        log.debug("MobiData BW reported {} live charge point(s) for bounds {}", availability.size(), bounds);
        return availability;
    }

    /**
     * Reads one page into {@code into}.
     *
     * @return the offset of the next page, or {@code -1} when there is none — an empty or failed page,
     * or the reported total reached. Without a total the only end marker is an empty page, so paging
     * goes on to the cap.
     */
    private int readPage(GeoBounds bounds, int offset, List<ChargePointAvailability> into) {
        OcpdbResponses.LocationPage response = requestPage(bounds, offset);
        List<OcpdbResponses.Location> items = response == null ? null : response.items();
        if (items == null || items.isEmpty()) return -1;

        for (OcpdbResponses.Location location : items) collect(location, into);
        int next = offset + items.size();
        Integer total = response.totalCount();
        return total != null && next >= total ? -1 : next;
    }

    /**
     * One page of locations in the box.
     * <p>
     * Returns {@code null} rather than throwing on any upstream failure. A national access point is
     * allowed to be down, slow or malformed; the contract with {@link AvailabilityProvider} is that
     * this degrades the area to unknown, and the station or map around it keeps working.
     */
    private OcpdbResponses.LocationPage requestPage(GeoBounds bounds, int offset) {
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
                    .body(OcpdbResponses.LocationPage.class);
        } catch (RuntimeException e) {
            log.warn("MobiData BW request failed for bounds {} at offset {} — area answers UNKNOWN", bounds, offset, e);
            return null;
        }
    }

    private static void collect(OcpdbResponses.Location location, List<ChargePointAvailability> into) {
        if (location.chargingPool() == null) return;
        for (OcpdbResponses.ChargeStation chargeStation : location.chargingPool()) {
            if (chargeStation != null && chargeStation.evses() != null) {
                for (OcpdbResponses.Evse evse : chargeStation.evses()) {
                    ChargePointAvailability availability = toAvailability(evse);
                    if (availability != null) into.add(availability);
                }
            }
        }
    }

    /** One EVSE's live state, or {@code null} when it carries none or cannot be joined. */
    private static ChargePointAvailability toAvailability(OcpdbResponses.Evse evse) {
        String status = OcpiStatus.toLiveAvailability(evse.status());
        // Null means the status carries no live information — a static register copy, or a charge
        // point that is planned or removed. Emitting it would put a live badge on a station that has no
        // live feed behind it.
        if (status == null) return null;

        String evseId = identifierOf(evse);
        if (evseId == null) return null;

        // status_last_updated is the timestamp that moves when the status does; last_updated describes
        // the static record and is sometimes years old.
        Instant observedAt = evse.statusLastUpdated() != null ? evse.statusLastUpdated() : evse.lastUpdated();
        return new ChargePointAvailability(evseId, status, observedAt);
    }

    /**
     * The normalized identifier to join on, preferring the published EVSE-ID and falling back to the
     * feed's own id when OCPDB rewrote it.
     * <p>
     * The {@code BNETZA*<Ladeeinrichtungs-ID>*<n>} form is deliberately not treated as an EVSE-ID: it
     * is OCPDB's own synthesis, no operator publishes it, and normalizing it would produce a key that
     * collides with nothing in the register. It is skipped here rather than emitted as noise.
     */
    private static String identifierOf(OcpdbResponses.Evse evse) {
        String fromEvseId = usableIdentifier(evse.evseId());
        if (fromEvseId != null) return fromEvseId;
        return usableIdentifier(evse.originalUid());
    }

    private static String usableIdentifier(String candidate) {
        if (candidate == null || candidate.isBlank()) return null;
        Matcher synthetic = BNETZA_SYNTHETIC.matcher(candidate.trim());
        if (synthetic.matches()) return null;
        return EvseIds.normalize(candidate);
    }
}
