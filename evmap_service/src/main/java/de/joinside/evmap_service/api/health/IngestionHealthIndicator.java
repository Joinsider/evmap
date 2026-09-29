package de.joinside.evmap_service.api.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Reports the ingestion job's state on the API's {@code /actuator/health}.
 * <p>
 * The sync deployable runs with {@code web-application-type: none} and can expose nothing itself, so
 * the API reads the run history it writes — read-only, like all other master data.
 * <p>
 * Deliberately <em>not</em> part of the {@code startup} health group the container healthcheck uses:
 * a failed ingestion must never make the API container unhealthy, or a cold start would deadlock
 * (sync waits for the API, the API waits for a sync that has never run).
 */
@Component("ingestion")
class IngestionHealthIndicator implements HealthIndicator {
    private static final Logger log = LoggerFactory.getLogger(IngestionHealthIndicator.class);

    private static final String LAST_SUCCESS_AT = "lastSuccessAt";

    private final JdbcClient jdbc;
    private final Duration maxAge;

    // Defaulted in code as well as in application.yaml: a missing threshold must not stop the API from booting.
    IngestionHealthIndicator(JdbcClient jdbc, @Value("${evmap.sync.max-age:PT48H}") Duration maxAge) {
        this.jdbc = jdbc;
        this.maxAge = maxAge;
    }

    @Override
    public Health health() {
        Optional<SyncRun> last = lastCompletedRun();
        if (last.isEmpty()) return Health.up().withDetail("lastRun", "none").build();

        SyncRun run = last.get();
        Health.Builder health = "FAILED".equals(run.status()) ? Health.down() : staleness();
        return health
                .withDetail("lastRunStatus", run.status())
                .withDetail("lastRunAt", run.finishedAt())
                .withDetail("processed", run.processed())
                .withDetail("created", run.created())
                .withDetail("updated", run.updated())
                .withDetail("failed", run.failed())
                .withDetail("errorMessage", run.errorMessage() == null ? "" : run.errorMessage())
                .build();
    }

    /**
     * Staleness only counts once a run has actually ingested something. A brand-new deployment has
     * only SKIPPED runs, and reporting DOWN for a system behaving exactly as designed is noise.
     */
    private Health.Builder staleness() {
        // PARTIAL counts as a success for staleness: such a run did commit its data, it just could
        // not ingest every record. Excluding it would report the whole ingestion stale over a handful
        // of bad rows that the next run retries anyway.
        Instant lastSuccess = jdbc.sql("SELECT max(finished_at) FROM master.sync_run WHERE status IN ('SUCCEEDED','PARTIAL')")
                .query(Instant.class).optional().orElse(null);
        if (lastSuccess == null) return Health.up().withDetail(LAST_SUCCESS_AT, "none");

        Duration age = Duration.between(lastSuccess, Instant.now());
        if (age.compareTo(maxAge) <= 0) return Health.up().withDetail(LAST_SUCCESS_AT, lastSuccess);

        log.warn("Last successful ingestion was {} ago, over the {} threshold", age, maxAge);
        return Health.down().withDetail(LAST_SUCCESS_AT, lastSuccess).withDetail("age", age.toString());
    }

    private Optional<SyncRun> lastCompletedRun() {
        try {
            return jdbc.sql("SELECT status, finished_at, processed, created, updated, failed, error_message " +
                            "FROM master.sync_run WHERE finished_at IS NOT NULL ORDER BY finished_at DESC LIMIT 1")
                    .query((rs, row) -> new SyncRun(rs.getString("status"), rs.getTimestamp("finished_at").toInstant(),
                            rs.getInt("processed"), rs.getInt("created"), rs.getInt("updated"), rs.getInt("failed"),
                            rs.getString("error_message")))
                    .optional();
        } catch (RuntimeException exception) {
            // A health check must not throw — an unreadable history is itself the finding.
            log.warn("Could not read ingestion history: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private record SyncRun(String status, Instant finishedAt, int processed, int created, int updated,
                           int failed, String errorMessage) {
    }
}
