package de.joinside.evmap_service.sync;

import de.joinside.evmap_service.logging.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Drives one sync run: every enabled source, one after another, into the ingestion port.
 * <p>
 * Knows no source by name — it takes whatever adapters the component scan found, which is what keeps
 * adding a country to a new package rather than an edit here.
 */
@Component
@ConditionalOnProperty(name = "evmap.sync.enabled", havingValue = "true")
class SyncJob {
    private static final Logger log = LoggerFactory.getLogger(SyncJob.class);
    private static final String JOB_NAME = "station-sync";

    private final List<SourceAdapter> adapters;
    private final StationIngestionPort ingestion;
    private final List<String> supersedingSources;

    /**
     * @param supersedingSources sources whose stations hide the ones they make redundant, recomputed after every run
     *                           ({@code evmap.sync.supersede}, ADR 0025). Configuration, because this class names no
     *                           source.
     */
    @Autowired
    SyncJob(List<SourceAdapter> adapters, StationIngestionPort ingestion,
            @Value("${evmap.sync.supersede:}") List<String> supersedingSources) {
        this.adapters = adapters;
        this.ingestion = ingestion;
        this.supersedingSources = supersedingSources.stream().map(String::trim).filter(token -> !token.isEmpty()).toList();
    }

    SyncJob(List<SourceAdapter> adapters, StationIngestionPort ingestion) {
        this(adapters, ingestion, List.of());
    }

    @Scheduled(fixedDelayString = "${evmap.sync.fixed-delay}")
    void synchronize() {
        // The sync deployable has no HTTP request to hang a correlation id on, so the job names itself.
        try (var _ = LogContext.scope(LogContext.JOB, JOB_NAME)) {
            UUID runId = ingestion.startRun();

            List<SourceAdapter> active = activeAdapters();
            if (active.isEmpty()) {
                String reason = adapters.isEmpty()
                        ? "No source adapters registered"
                        : "Every registered source adapter is disabled";
                log.warn("Sync run skipped: {}", reason);
                ingestion.finishRun(runId, StationIngestionPort.RunStatus.SKIPPED, null, reason);
                return;
            }

            log.info("Sync run {} started with {} source(s): {}", runId, active.size(),
                    active.stream().map(SourceAdapter::source).toList());
            long startedAt = System.nanoTime();
            try {
                List<SourceAdapterRun> runs = ingestAll(active);
                supersedeDuplicates();
                StationIngestionPort.IngestionResult total = totalOf(runs);

                Duration took = Duration.ofNanos(System.nanoTime() - startedAt);
                log.info("Sync run finished in {}: {} processed, {} created, {} updated, {} unchanged, {} failed",
                        took, total.processed(), total.created(), total.updated(), total.unchanged(), total.failed());
                ingestion.finishRun(runId, statusOf(runs, total), total, failureSummaryOf(runs));
            } catch (RuntimeException exception) {
                // Swallowed on purpose: the scheduler must keep the next run scheduled after a bad day.
                // Reaching here means the ingestion itself broke — a source failing is contained in
                // SourceAdapterRun and never gets this far. The failure survives in master.sync_run.
                log.error("Sync run failed after {}: {}", Duration.ofNanos(System.nanoTime() - startedAt),
                        exception.getMessage(), exception);
                ingestion.finishRun(runId, StationIngestionPort.RunStatus.FAILED, null, exception.toString());
            }
        }
    }

    private List<SourceAdapter> activeAdapters() {
        List<SourceAdapter> active = new ArrayList<>(adapters.size());
        for (SourceAdapter adapter : adapters) {
            if (adapter.enabled()) active.add(adapter);
            else log.info("Source {} disabled by configuration", adapter.source());
        }
        return active;
    }

    /**
     * Ingests each source separately rather than composing one stream across all of them.
     * <p>
     * Three things follow from that, and all three get harder to live without as sources are added:
     * the MDC carries the right source for the whole time that source is being ingested; the result is
     * attributable per source instead of only per run; and {@link SourceAdapter#commitProgress()} can
     * be decided on that source's own outcome. The last one matters most — under a shared stream a
     * single bad record anywhere held back every incremental source's watermark (ADR 0006, open point
     * 3), so one broken French row cost Open Charge Map a day of progress.
     * <p>
     * Batching is unaffected: each ingestion commits in batches of its own, so nothing grows with the
     * number of sources.
     */
    private List<SourceAdapterRun> ingestAll(List<SourceAdapter> active) {
        List<SourceAdapterRun> runs = new ArrayList<>(active.size());
        for (SourceAdapter adapter : active) {
            SourceAdapterRun run = new SourceAdapterRun(adapter);
            runs.add(run);

            try (var _ = LogContext.scope(LogContext.SOURCE, adapter.source())) {
                long startedAt = System.nanoTime();
                StationIngestionPort.IngestionResult result;
                // Closing releases whatever the adapter holds open even when the ingestion throws.
                try (Stream<SourceStation> stations = run.stations()) {
                    result = ingestion.upsert(stations);
                }
                run.setResult(result);

                log.info("Source {} contributed {} station(s) in {}: {} created, {} updated, {} unchanged, {} failed",
                        adapter.source(), run.fetched(), Duration.ofNanos(System.nanoTime() - startedAt),
                        result.created(), result.updated(), result.unchanged(), result.failed());

                if (run.succeeded() && result.failed() == 0) {
                    // Only now is it true that everything this source fetched was also stored, which is
                    // the precondition for advancing an incremental watermark.
                    adapter.commitProgress();
                } else if (run.succeeded()) {
                    log.warn("Source {} could not ingest {} of {} stations; it will re-fetch its window "
                            + "rather than advance", adapter.source(), result.failed(), result.processed());
                }
            }
        }
        return runs;
    }

    /**
     * After every source, because the marks depend on all of them: a register entry the authority made redundant
     * yesterday may have gained an EVSE-ID of its own today. Recomputed even when the superseding source failed —
     * its stations from earlier runs are still there, and the marks describe them.
     */
    private void supersedeDuplicates() {
        for (String source : supersedingSources) {
            try (var _ = LogContext.scope(LogContext.SOURCE, source)) {
                int superseded = ingestion.supersedeDuplicates(source);
                log.info("Source {} supersedes {} station(s)", source, superseded);
            }
        }
    }

    private static StationIngestionPort.IngestionResult totalOf(List<SourceAdapterRun> runs) {
        int processed = 0;
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int failed = 0;
        for (SourceAdapterRun run : runs) {
            StationIngestionPort.IngestionResult result = run.result();
            processed += result.processed();
            created += result.created();
            updated += result.updated();
            unchanged += result.unchanged();
            failed += result.failed();
        }
        return new StationIngestionPort.IngestionResult(processed, created, updated, unchanged, failed);
    }

    /**
     * A run that lost a source, or a record, is {@code PARTIAL}: it committed real data and saying so
     * is more useful than a binary verdict. It is only {@code FAILED} when every source broke and
     * nothing was ingested at all — at that point "partially succeeded" would be untrue.
     */
    private static StationIngestionPort.RunStatus statusOf(List<SourceAdapterRun> runs,
                                                           StationIngestionPort.IngestionResult total) {
        boolean allFailed = runs.stream().noneMatch(SourceAdapterRun::succeeded);
        if (allFailed && total.processed() == 0) return StationIngestionPort.RunStatus.FAILED;

        boolean complete = total.failed() == 0 && runs.stream().allMatch(SourceAdapterRun::succeeded);
        return complete ? StationIngestionPort.RunStatus.SUCCEEDED : StationIngestionPort.RunStatus.PARTIAL;
    }

    /** Names the sources that broke, so {@code master.sync_run} says which country to go looking at. */
    private static String failureSummaryOf(List<SourceAdapterRun> runs) {
        List<String> failures = runs.stream()
                .filter(run -> !run.succeeded())
                .map(run -> run.source() + ": " + run.failure())
                .toList();
        return failures.isEmpty() ? null : String.join("; ", failures);
    }
}
