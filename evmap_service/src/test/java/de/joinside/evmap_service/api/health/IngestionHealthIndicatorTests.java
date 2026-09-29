package de.joinside.evmap_service.api.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class IngestionHealthIndicatorTests {
    private final JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);

    private IngestionHealthIndicator indicator() {
        return new IngestionHealthIndicator(jdbc, Duration.ofHours(48));
    }

    /** Runs the production row mapper against a stubbed result set, so its private record is built by its own code. */
    private void lastRun(String status) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("status")).thenReturn(status);
        when(rs.getTimestamp("finished_at")).thenReturn(Timestamp.from(Instant.now()));
        when(jdbc.sql(contains("finished_at IS NOT NULL")).query(any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(0);
                    Object row = mapper.mapRow(rs, 0);
                    JdbcClient.MappedQuerySpec spec = mock(JdbcClient.MappedQuerySpec.class);
                    when(spec.optional()).thenReturn(Optional.of(row));
                    return spec;
                });
    }

    private void lastSuccess(Instant at) {
        when(jdbc.sql(contains("SUCCEEDED")).query(Instant.class).optional()).thenReturn(Optional.ofNullable(at));
    }

    @Test
    void upWithNoRunsYet() {
        when(jdbc.sql(contains("finished_at IS NOT NULL")).query(any(RowMapper.class)).optional())
                .thenReturn(Optional.empty());
        assertThat(indicator().health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void downWhenTheLastRunFailed() throws Exception {
        lastRun("FAILED");
        assertThat(indicator().health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void upWhenNothingHasSucceededYet() throws Exception {
        lastRun("SKIPPED");
        lastSuccess(null);
        Health health = indicator().health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("lastSuccessAt", "none");
    }

    @Test
    void upWhenTheLastSuccessIsRecent() throws Exception {
        lastRun("SUCCEEDED");
        lastSuccess(Instant.now().minus(Duration.ofHours(1)));
        assertThat(indicator().health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void downWhenTheLastSuccessIsStale() throws Exception {
        lastRun("SUCCEEDED");
        lastSuccess(Instant.now().minus(Duration.ofHours(72)));
        Health health = indicator().health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("age");
    }
}
