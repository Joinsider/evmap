package de.joinside.evmap_service.api.station;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class OperatorSearchTests {

    @DisplayName("a query with nothing in it means 'no name filter', not 'match everything'")
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void treatsBlankQueryAsNoFilter(String query) {
        assertThat(OperatorSearch.of(query, 50).pattern()).isNull();
    }

    @Test
    @DisplayName("a query becomes a substring pattern, with the surrounding whitespace dropped")
    void wrapsQueryInWildcards() {
        assertThat(OperatorSearch.of("  ionity ", 50).pattern()).isEqualTo("%ionity%");
    }

    @Test
    @DisplayName("LIKE metacharacters the user typed stay literal — '50%' must not match everything after '50'")
    void escapesLikeMetacharacters() {
        assertThat(OperatorSearch.of("50%", 50).pattern()).isEqualTo("%50\\%%");
        assertThat(OperatorSearch.of("e_on", 50).pattern()).isEqualTo("%e\\_on%");
        assertThat(OperatorSearch.of("back\\slash", 50).pattern()).isEqualTo("%back\\\\slash%");
        assertThat(OperatorSearch.of("100%_pure", 50).pattern()).isEqualTo("%100\\%\\_pure%");
    }

    @DisplayName("the row limit is bounded on both ends")
    @ParameterizedTest
    @ValueSource(ints = {0, -1, OperatorSearch.MAX_LIMIT + 1, Integer.MAX_VALUE})
    void rejectsLimitOutsideBounds(int limit) {
        assertThatIllegalArgumentException().isThrownBy(() -> OperatorSearch.of("ionity", limit));
    }

    @DisplayName("the bounds themselves are allowed")
    @ParameterizedTest
    @ValueSource(ints = {1, OperatorSearch.MAX_LIMIT})
    void acceptsLimitAtBounds(int limit) {
        assertThat(OperatorSearch.of(null, limit).limit()).isEqualTo(limit);
    }
}
