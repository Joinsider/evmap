package de.joinside.evmap_service.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SourceAuthorityTests {

    @Test
    @DisplayName("answers the authoritative source of a country, whatever the case")
    void looksUpCaseInsensitively() {
        var authority = new SourceAuthority(Map.of("de", "BNetzA", " CH ", " DIEMO "));

        assertThat(authority.sourceFor("DE")).contains("BNetzA");
        assertThat(authority.sourceFor("ch")).contains("DIEMO");
    }

    @Test
    @DisplayName("has no answer for a country without an entry, and none for a missing country")
    void emptyForUnknownCountries() {
        var authority = new SourceAuthority(Map.of("DE", "BNetzA"));

        assertThat(authority.sourceFor("FR")).isEmpty();
        assertThat(authority.sourceFor(null)).isEmpty();
        assertThat(SourceAuthority.none().sourceFor("DE")).isEmpty();
    }

    @Test
    @DisplayName("drops entries that name no country or no source instead of failing at startup")
    void ignoresBlankEntries() {
        Map<String, String> configured = new HashMap<>();
        configured.put("", "IRVE");
        configured.put("FR", " ");
        configured.put("AT", null);
        configured.put("DE", "BNetzA");

        assertThat(new SourceAuthority(configured).authority()).containsOnlyKeys("DE");
        assertThat(new SourceAuthority(null).authority()).isEmpty();
    }
}
