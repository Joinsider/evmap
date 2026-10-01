package de.joinside.evmap_service.sync.es;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param enabled turns the adapter off without removing it from the deployment
 * @param url     the DATEX II v3 publication of the Spanish register on the DGT's National Access
 *                Point. It needs no key and is refreshed every 24 h. Override to pin a copy or to point
 *                the adapter at a local file server during development.
 * @param timeout per-request read timeout; the file is about 83 MB
 */
@ConfigurationProperties("evmap.sync.miterd")
record MiterdProperties(@DefaultValue("true") boolean enabled,
                        @DefaultValue("https://nap.dgt.es/datex2/v3/miterd/EnergyInfrastructureTablePublication/electrolineras.xml")
                        String url,
                        @DefaultValue("5m") Duration timeout) {
}
