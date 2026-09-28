package de.joinside.evmap_service.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole of live availability rests on these comparisons: a normalization that drifted between
 * the two sides of the join would not fail, it would quietly stop matching and look like a coverage
 * problem. Spellings below are real, taken from the 2026-07-28 BNetzA edition and MobiData BW.
 */
class EvseIdsTests {

    @Test
    @DisplayName("collapses the separator spellings publishers actually use onto one key")
    void collapsesSeparators() {
        // The same charge point, as the register writes it and as the OCPI feed does.
        assertThat(EvseIds.normalize("DE*EBW*E912316*1")).isEqualTo("DEEBWE9123161");
        assertThat(EvseIds.normalize("DEEBWE9123161")).isEqualTo("DEEBWE9123161");
        assertThat(EvseIds.normalize("de*ebw*e912316*1")).isEqualTo("DEEBWE9123161");
        // 1.192 rows in the register put spaces where the standard allows separators.
        assertThat(EvseIds.normalize("DE CSA 24D 006")).isEqualTo("DECSA24D006");
    }

    @Test
    @DisplayName("matches the compact form MobiData BW publishes against the register's starred one")
    void matchesAcrossSources() {
        assertThat(EvseIds.normalize("DE*AEW*E009903"))
                .isEqualTo(EvseIds.normalize("DEAEWE009903"));
    }

    @Test
    @DisplayName("answers null where there is no identifier to join on")
    void rejectsUnusableValues() {
        // Not an error — 69,7 % of declared Ladepunkte publish no EVSE-ID, and such a charge point is
        // ingested normally and simply never resolves to a live status.
        assertThat(EvseIds.normalize(null)).isNull();
        assertThat(EvseIds.normalize("")).isNull();
        assertThat(EvseIds.normalize("   ")).isNull();
        // Punctuation only, which normalizes to nothing and would otherwise become an empty key that
        // every other empty key collides with.
        assertThat(EvseIds.normalize("***")).isNull();
    }

    @Test
    @DisplayName("clips to the column width rather than failing the insert")
    void clipsToColumnWidth() {
        String overlong = "DE*ABC*E" + "9".repeat(200);
        assertThat(EvseIds.normalize(overlong)).hasSize(64);
    }
}
