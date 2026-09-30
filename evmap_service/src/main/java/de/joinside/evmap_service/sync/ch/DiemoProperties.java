package de.joinside.evmap_service.sync.ch;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param enabled turns the adapter off without removing it from the deployment
 * @param url     the static OICP feed on {@code data.geo.admin.ch}. It needs no key and is republished
 *                continuously. Override to pin a copy or to point the adapter at a local file server
 *                during development. The separate {@code status/} feed is live occupancy and is
 *                deliberately not configurable here: it never enters master data (ADR 0015).
 * @param timeout per-request read timeout; the file is about 1 MB compressed
 */
@ConfigurationProperties("evmap.sync.diemo")
record DiemoProperties(@DefaultValue("true") boolean enabled,
                       @DefaultValue("https://data.geo.admin.ch/ch.bfe.ladestellen-elektromobilitaet/data/oicp/ch.bfe.ladestellen-elektromobilitaet.json")
                       String url,
                       @DefaultValue("2m") Duration timeout) {
}
