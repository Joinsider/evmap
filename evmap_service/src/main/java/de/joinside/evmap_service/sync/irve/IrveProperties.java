package de.joinside.evmap_service.sync.irve;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param enabled turns the adapter off without removing it from the deployment
 * @param csvUrl  the consolidated file. The default is data.gouv.fr's stable resource id, which
 *                redirects to the current date-stamped edition on {@code static.data.gouv.fr} — unlike
 *                the BNetzA register there is nothing to scrape, the id never changes. Override to pin
 *                an edition or to point the adapter at a local copy during development.
 * @param timeout per-request read timeout; the file was 162 MB / 231.647 charge points on 2026-07-29,
 *                and it grows with every new French charge point
 */
@ConfigurationProperties("evmap.sync.irve")
record IrveProperties(@DefaultValue("true") boolean enabled,
                      @DefaultValue("https://www.data.gouv.fr/api/1/datasets/r/eb76d20a-8501-400e-b336-d85724de5435")
                      String csvUrl,
                      @DefaultValue("10m") Duration timeout) {
}
