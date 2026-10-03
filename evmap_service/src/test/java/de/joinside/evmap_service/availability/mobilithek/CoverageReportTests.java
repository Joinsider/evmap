package de.joinside.evmap_service.availability.mobilithek;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CoverageReportTests {
    private static final Set<String> STORED = Set.of("DEEWEE000501S0401", "DEEWEE000501S0402", "DEEBWE9123161",
            "DEAEWE002501", "PARKHAUS7");

    @Test
    @DisplayName("an operator prefix is the country code and operator id of an EVSE-ID, and nothing else has one")
    void prefixes() {
        assertThat(CoverageReport.prefixOf("DEEWEE000501S0401")).isEqualTo("DEEWE");
        assertThat(CoverageReport.prefixOf("54BCECEA3BE75033A5060F0C75B86A21")).isNull();
        assertThat(CoverageReport.byPrefix(STORED))
                .isEqualTo(Map.of("DEEWE", 2L, "DEEBW", 1L, "DEAEW", 1L));
    }

    @Test
    @DisplayName("a feed's coverage tells matched, untranslated and unmatched apart and names what could match")
    void feed() {
        Set<String> live = Set.of("DEEWEE000501S0401", "DEEWEE000501S0403", "DEEWEE000501S0404", "DEEWEE000501S0405",
                "DEEWEE000501S0406", "DEABCE1", "1006184570533171200");

        CoverageReport.Feed coverage =
                CoverageReport.forFeed("EWE", live, STORED, CoverageReport.byPrefix(STORED));

        assertThat(coverage.publisher()).isEqualTo("EWE");
        assertThat(coverage.live()).isEqualTo(7);
        assertThat(coverage.matched()).isOne();
        assertThat(coverage.untranslated()).isOne();
        // Most frequent first; DEABC has no stored EVSE-ID, so only EWE's two count.
        assertThat(coverage.prefixes()).containsExactly("DEEWE", "DEABC");
        assertThat(coverage.otherPrefixes()).isZero();
        assertThat(coverage.prefixesText()).isEqualTo("[DEEWE, DEABC]");
        assertThat(coverage.storedWithPrefixes()).isEqualTo(2);
        assertThat(coverage.unmatchedSamples())
                .containsExactly("DEABCE1", "DEEWEE000501S0403", "DEEWEE000501S0404");
    }

    @Test
    @DisplayName("a platform feed names its five most frequent prefixes and counts the rest")
    void platformFeed() {
        Set<String> live = Set.of("DEAAAE1", "DEAAAE2", "DEBBBE1", "DECCCE1", "DEDDDE1", "DEEEEE1", "DEFFFE1");

        CoverageReport.Feed coverage = CoverageReport.forFeed("chargecloud", live, Set.of(), Map.of());

        assertThat(coverage.prefixes()).containsExactly("DEAAA", "DEBBB", "DECCC", "DEDDD", "DEEEE");
        assertThat(coverage.otherPrefixes()).isOne();
        assertThat(coverage.prefixesText()).isEqualTo("[DEAAA, DEBBB, DECCC, DEDDD, DEEEE] (+1 more)");
        assertThat(coverage.storedWithPrefixes()).isZero();
    }

    @Test
    @DisplayName("the total counts an EVSE-ID that two feeds relay once")
    void total() {
        CoverageReport.Total total = CoverageReport.total(10, STORED,
                List.of(Set.of("DEEWEE000501S0401", "DEXYZE1"), Set.of("DEEWEE000501S0401", "DEEBWE9123161")));

        assertThat(total).isEqualTo(new CoverageReport.Total(10, 5, 3, 2));
    }
}
