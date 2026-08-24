package de.joinside.evmap_service.availability.mobidata;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * @param enabled      turns the provider off without removing it from the deployment
 * @param baseUrl      the OCPDB integration platform. OCPI 3.0 since March 2026; the 2.2 paths are
 *                     still served but carry no charge-station level and no {@code status_last_updated}.
 * @param countryCodes countries this provider claims. MobiData BW is a Baden-Württemberg programme,
 *                     but its OCPDB consolidates DATEX II feeds that reach across Germany and mirrors
 *                     opendata.swiss — density is highest in BW and thins out from there, which is a
 *                     coverage fact rather than a scope one.
 * @param pageSize     locations per request. The response carries every EVSE and connector of each
 *                     location, so this trades request count against response size.
 * @param maxPages     ceiling per query, so an unexpectedly dense viewport cannot turn one map pan
 *                     into an unbounded crawl.
 * @param timeout      per-request connect and read timeout. A live source that is slow must fail fast
 *                     and leave the area unknown, never hold the station detail request open.
 */
@ConfigurationProperties("evmap.availability.mobidata")
record MobiDataProperties(@DefaultValue("true") boolean enabled,
                          @DefaultValue("https://api.mobidata-bw.de/ocpdb/api/ocpi/3.0") String baseUrl,
                          @DefaultValue({"DE", "CH"}) List<String> countryCodes,
                          @DefaultValue("200") int pageSize,
                          @DefaultValue("5") int maxPages,
                          @DefaultValue("10s") Duration timeout) {
}
