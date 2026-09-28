package de.joinside.evmap_service.availability.irve;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * @param enabled         turns the provider off without removing it from the deployment
 * @param csvUrl          the national consolidation of every publisher's {@code schema-irve-dynamique}
 *                        file. The data.gouv.fr resource id is the stable address; it redirects to
 *                        transport.data.gouv.fr's proxy, which regenerates the file per request.
 * @param countryCodes    countries this provider claims. The consolidation is France-only.
 * @param refreshInterval how long one download is reused. The whole country is one file, so this is
 *                        the only knob on freshness and on upstream traffic alike: one gzipped
 *                        download is ~1,6 MB, and about a thousand charge points change per 90 s.
 * @param maxAge          oldest {@code horodatage} still reported. A quarter of the consolidation is
 *                        months old — publishers whose feed stopped rather than charge points that
 *                        stayed free — and a stale "free" is the one error users drive to.
 * @param staleAfter      how long the last good download is still served when refreshing fails.
 *                        Beyond it the provider answers nothing, and France reads as unknown.
 * @param timeout         per-request connect and read timeout. A request that triggers a refresh
 *                        waits for it, so this bounds how long one station detail can hang.
 */
@ConfigurationProperties("evmap.availability.irve")
record IrveDynamicProperties(@DefaultValue("true") boolean enabled,
                             @DefaultValue("https://www.data.gouv.fr/api/1/datasets/r/89185b1f-f958-4c5b-9282-399a66ecee97") String csvUrl,
                             @DefaultValue("FR") List<String> countryCodes,
                             @DefaultValue("60s") Duration refreshInterval,
                             @DefaultValue("72h") Duration maxAge,
                             @DefaultValue("10m") Duration staleAfter,
                             @DefaultValue("15s") Duration timeout) {
}
