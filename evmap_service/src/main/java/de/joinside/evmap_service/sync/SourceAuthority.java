package de.joinside.evmap_service.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Which source is authoritative for which country — {@code evmap.sync.authority}, country code to
 * {@link SourceAdapter#source() source token}.
 * <p>
 * Every national register outranks the community crawl for its own country: the register is the
 * operator's legally required report, the crawl is what volunteers typed in. Without a rule the source
 * that happened to run last replaced a station's fields and its charge points, and adapters have no
 * defined order, so a run of Open Charge Map after a register would strip the register's EVSE-IDs
 * from every station the two had in common. See ADR 0012, "Switzerland (L2)".
 * <p>
 * This is configuration rather than code because {@code sync} names no source (see the package
 * documentation): the mapping lives in {@code application-sync.yaml} beside each adapter's own block.
 * A country with no entry has no authority and the most recent source wins, as before.
 *
 * @param authority country code → source token; keys are matched case-insensitively
 */
@ConfigurationProperties("evmap.sync")
record SourceAuthority(@DefaultValue Map<String, String> authority) {

    SourceAuthority {
        Map<String, String> normalized = new HashMap<>();
        if (authority != null) {
            authority.forEach((country, source) -> {
                if (country != null && !country.isBlank() && source != null && !source.isBlank())
                    normalized.put(country.trim().toUpperCase(Locale.ROOT), source.trim());
            });
        }
        authority = Map.copyOf(normalized);
    }

    static SourceAuthority none() {
        return new SourceAuthority(Map.of());
    }

    /** @return the authoritative source for {@code countryCode}, empty when the country has none */
    Optional<String> sourceFor(String countryCode) {
        if (countryCode == null) return Optional.empty();
        return Optional.ofNullable(authority.get(countryCode.trim().toUpperCase(Locale.ROOT)));
    }
}
