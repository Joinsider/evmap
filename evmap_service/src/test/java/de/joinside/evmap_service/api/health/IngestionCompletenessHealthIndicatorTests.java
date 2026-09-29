package de.joinside.evmap_service.api.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class IngestionCompletenessHealthIndicatorTests {
    private final JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);

    private IngestionCompletenessHealthIndicator indicator() {
        return new IngestionCompletenessHealthIndicator(jdbc);
    }

    /** Runs the production row mapper against a stubbed result set, so its private record is built by its own code. */
    private void lastRun(String status, int failed) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("status")).thenReturn(status);
        when(rs.getTimestamp("finished_at")).thenReturn(Timestamp.from(Instant.now()));
        when(rs.getInt("failed")).thenReturn(failed);
        when(jdbc.sql(anyString()).query(any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(0);
                    Object row = mapper.mapRow(rs, 0);
                    JdbcClient.MappedQuerySpec spec = mock(JdbcClient.MappedQuerySpec.class);
                    when(spec.optional()).thenReturn(Optional.of(row));
                    return spec;
                });
    }

    @Test
    void upWithNoRunsYet() {
        when(jdbc.sql(anyString()).query(any(RowMapper.class)).optional()).thenReturn(Optional.empty());
        assertThat(indicator().health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void incompleteWhenTheLastRunWasPartial() throws Exception {
        lastRun("PARTIAL", 12);
        Health health = indicator().health();
        assertThat(health.getStatus()).isEqualTo(IngestionCompletenessHealthIndicator.INCOMPLETE);
        assertThat(health.getDetails()).containsEntry("failed", 12);
    }

    @Test
    void upWhenTheLastRunSucceeded() throws Exception {
        lastRun("SUCCEEDED", 0);
        assertThat(indicator().health().getStatus()).isEqualTo(Status.UP);
    }

    /** A failed run is IngestionHealthIndicator's finding; reporting it twice would blur the two signals. */
    @Test
    void upWhenTheLastRunFailed() throws Exception {
        lastRun("FAILED", 0);
        assertThat(indicator().health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void upWhenTheHistoryCannotBeRead() {
        when(jdbc.sql(anyString())).thenThrow(new IllegalStateException("relation does not exist"));
        assertThat(indicator().health().getStatus()).isEqualTo(Status.UP);
    }
}
