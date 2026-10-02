package de.joinside.evmap_service.pricing.mobidata;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.Period;
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
 * @param vatBasis      operators whose publishing basis was checked by hand against their own price pages (ADR 0022,
 *                      phase 5r), as OCPDB names them on the location. Matched case-insensitively.
 * @param recheckAfter  how old a check may get before startup warns that it is due again
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
                                 @DefaultValue("10s") Duration timeout,
                                 List<OperatorBasis> vatBasis,
                                 @DefaultValue("P6M") Period recheckAfter) {

    /**
     * One checked operator.
     *
     * @param operator  the operator name exactly as OCPDB writes it on the location
     * @param basis     {@code NET} or {@code GROSS}: how the operator's energy prices reach the feed
     * @param checkedOn when the official price page (or a recent third-party source) was compared
     * @param source    where: the page's URL, so the next check starts there
     */
    record OperatorBasis(String operator,
                         OcpiTariffs.TableBasis basis,
                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkedOn,
                         String source) {
    }
}
