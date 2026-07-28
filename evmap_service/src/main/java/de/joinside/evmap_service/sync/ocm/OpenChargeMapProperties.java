package de.joinside.evmap_service.sync.ocm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * @param enabled            turns the adapter off without removing it from the deployment
 * @param baseUrl            API root; overridable to point at a self-hosted OCM mirror
 * @param apiKey             mandatory — the API answers 403 without one. Blank disables the adapter
 * @param countryCodes       ISO country codes to ingest, one keyset-paged crawl each
 * @param pageSize           results per request; also the "was that the last page" threshold
 * @param requestDelay       pause between requests, to stay inside OCM's fair usage policy
 * @param maxPagesPerCountry safety stop, so a paging bug cannot turn into an unbounded crawl
 * @param openDataOnly       restrict to feeds under an open licence, which the app can attribute
 * @param timeout            per-request connect and read timeout
 * @param client             identifies this crawler to OCM, as their documentation asks
 * @param incremental        fetch only what changed since the last fully ingested run
 * @param watermarkOverlap   how far back before the watermark to re-ask, absorbing clock skew between
 *                           us and OCM and any record that was fetched but not committed
 * @param fullRefreshInterval how stale a watermark may get before the country is crawled in full
 *                            again; incremental fetches never see upstream deletions or edits OCM
 *                            does not count as a modification, so drift has to be swept periodically
 */
@ConfigurationProperties("evmap.sync.ocm")
record OpenChargeMapProperties(@DefaultValue("true") boolean enabled,
                               @DefaultValue("https://api.openchargemap.io/v3") String baseUrl,
                               @DefaultValue("") String apiKey,
                               @DefaultValue({"DE", "AT", "CH", "NL", "BE", "LU", "FR", "IT", "DK", "PL", "CZ"})
                               List<String> countryCodes,
                               @DefaultValue("500") int pageSize,
                               @DefaultValue("1s") Duration requestDelay,
                               @DefaultValue("200") int maxPagesPerCountry,
                               @DefaultValue("true") boolean openDataOnly,
                               @DefaultValue("1m") Duration timeout,
                               @DefaultValue("evmap") String client,
                               @DefaultValue("true") boolean incremental,
                               @DefaultValue("2d") Duration watermarkOverlap,
                               @DefaultValue("7d") Duration fullRefreshInterval) {
}
