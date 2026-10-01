package de.joinside.evmap_service.sync.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SourceTextTests {
    @Test
    @DisplayName("treats null and blank text as no value and trims the rest")
    void blankToNull() {
        assertThat(SourceText.blankToNull(null)).isNull();
        assertThat(SourceText.blankToNull("  \t")).isNull();
        assertThat(SourceText.blankToNull("  Palma ")).isEqualTo("Palma");
    }

    @Test
    @DisplayName("takes the first value that is there")
    void firstPresent() {
        assertThat(SourceText.firstPresent(null, "b", "c")).isEqualTo("b");
        assertThat(SourceText.firstPresent(null, null)).isNull();
    }

    @Test
    @DisplayName("picks the most frequent key, the first inserted on a tie, null for none")
    void mostFrequent() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        assertThat(SourceText.mostFrequent(counts)).isNull();
        counts.put("Endesa", 2);
        counts.put("Repsol", 3);
        counts.put("Iberdrola", 3);
        assertThat(SourceText.mostFrequent(counts)).isEqualTo("Repsol");
    }
}
