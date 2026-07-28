package de.joinside.evmap_service.sync.bnetza;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param enabled  turns the adapter off without removing it from the deployment
 * @param indexUrl page carrying the download links; scraped for the current CSV unless {@code csvUrl}
 *                 is set, because the published file name is date-stamped and changes every edition
 * @param csvUrl   explicit CSV URL that skips discovery — pins a known edition, or points the adapter
 *                 at a local copy during development
 * @param timeout  per-request read timeout; the CSV was 53 MB in the 2026-07-07 edition
 */
@ConfigurationProperties("evmap.sync.bnetza")
record BnetzaProperties(@DefaultValue("true") boolean enabled,
                        @DefaultValue("https://www.bundesnetzagentur.de/DE/Fachthemen/ElektrizitaetundGas/E-Mobilitaet/Ladesaeulenkarte/start.html")
                        String indexUrl,
                        @DefaultValue("") String csvUrl,
                        @DefaultValue("5m") Duration timeout) {
}
