package de.joinside.evmap_service.pricing.mobidata;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * @param enabled       turns the provider off without removing it
 * @param baseUrl       the OCPDB OCPI 3.0 endpoint, the same one live availability reads
 * @param countryCodes  countries asked about. Only the German AFIR feeds carry tariffs; the Swiss mirror has none.
 * @param pageSize      locations per request
 * @param maxPages      ceiling per area, so a dense box cannot become an unbounded crawl
 * @param tariffPageSize tariffs per request when the whole tariff list is refreshed (1.029 in October 2026)
 * @param maxTariffPages ceiling on that refresh
 * @param tariffTtl     how long the tariff list is reused before it is read again
 * @param timeout       per-request timeout; a slow source must leave the price unknown, not hold the screen
 * @see de.joinside.evmap_service.vatbasis.VatBasisProperties the operator table for tariffs without a VAT basis
 */
@ConfigurationProperties("evmap.pricing.mobidata")
record MobiDataPricingProperties(@DefaultValue("true") boolean enabled,
                                 @DefaultValue("https://api.mobidata-bw.de/ocpdb/api/ocpi/3.0") String baseUrl,
                                 @DefaultValue("DE") List<String> countryCodes,
                                 @DefaultValue("200") int pageSize,
                                 @DefaultValue("5") int maxPages,
                                 @DefaultValue("1000") int tariffPageSize,
                                 @DefaultValue("10") int maxTariffPages,
                                 @DefaultValue("15m") Duration tariffTtl,
                                 @DefaultValue("10s") Duration timeout) {
}
