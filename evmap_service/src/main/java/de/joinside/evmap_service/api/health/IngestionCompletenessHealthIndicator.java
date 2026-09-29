package de.joinside.evmap_service.api.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Reports whether the last ingestion run delivered <em>everything</em>, as opposed to
 * {@link IngestionHealthIndicator}, which reports whether it ran at all.
 * <p>
 * A {@code PARTIAL} run committed its data but lost records or a whole source (ADR 0013). That is
 * worth an alert, but not the same alert as a dead ingestion — so this indicator answers the custom
 * status {@link #INCOMPLETE}, which {@code application.yaml} orders <em>below</em> UP for the root
 * {@code /actuator/health} (unchanged from ADR 0004) and maps to 503 only in the {@code sync} health
 * group the monitoring asks separately. See ADR 0019.
 */
@Component("ingestionCompleteness")
class IngestionCompletenessHealthIndicator implements HealthIndicator {
    private static final Logger log = LoggerFactory.getLogger(IngestionCompletenessHealthIndicator.class);

    static final Status INCOMPLETE = new Status("INCOMPLETE", "The last ingestion run could not ingest every record or source");

    private final JdbcClient jdbc;

    IngestionCompletenessHealthIndicator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Health health() {
        Optional<LastRun> last = lastCompletedRun();
        if (last.isEmpty()) return Health.up().withDetail("lastRun", "none").build();

        LastRun run = last.get();
        Health.Builder health = "PARTIAL".equals(run.status()) ? Health.status(INCOMPLETE) : Health.up();
        return health
                .withDetail("lastRunStatus", run.status())
                .withDetail("lastRunAt", run.finishedAt())
                .withDetail("failed", run.failed())
                .build();
    }

    private Optional<LastRun> lastCompletedRun() {
        try {
            return jdbc.sql("SELECT status, finished_at, failed FROM master.sync_run " +
                            "WHERE finished_at IS NOT NULL ORDER BY finished_at DESC LIMIT 1")
                    .query((rs, row) -> new LastRun(rs.getString("status"),
                            rs.getTimestamp("finished_at").toInstant(), rs.getInt("failed")))
                    .optional();
        } catch (RuntimeException exception) {
            // Same stance as IngestionHealthIndicator: a health check must not throw.
            log.warn("Could not read ingestion history: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private record LastRun(String status, Instant finishedAt, int failed) {
    }
}
