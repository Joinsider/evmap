package de.joinside.evmap_service.sync;

import de.joinside.evmap_service.logging.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Component
@ConditionalOnProperty(name = "evmap.sync.enabled", havingValue = "true")
class SyncJob {
    private static final Logger log = LoggerFactory.getLogger(SyncJob.class);
    private static final String JOB_NAME = "station-sync";

    private final List<SourceAdapter> adapters;
    private final StationIngestionPort ingestion;

    SyncJob(List<SourceAdapter> adapters, StationIngestionPort ingestion) {
        this.adapters = adapters;
        this.ingestion = ingestion;
    }

    @Scheduled(fixedDelayString = "${evmap.sync.fixed-delay}")
    void synchronize() {
        // The sync deployable has no HTTP request to hang a correlation id on, so the job names itself.
        try (var scope = LogContext.scope(LogContext.JOB, JOB_NAME)) {
            UUID runId = ingestion.startRun();

            if (adapters.isEmpty()) {
                log.warn("Sync run skipped: no source adapters registered");
                ingestion.finishRun(runId, StationIngestionPort.RunStatus.SKIPPED, null, "No source adapters registered");
                return;
            }

            log.info("Sync run {} started with {} source adapter(s)", runId, adapters.size());
            long startedAt = System.nanoTime();
            try {
                StationIngestionPort.IngestionResult result;
                // Closing the composed stream releases whatever the adapters hold open — a buffered
                // download, an in-flight crawl — even when the ingestion throws partway through.
                try (Stream<SourceStation> stations = adapters.stream().flatMap(SourceAdapter::fetchStations)) {
                    result = ingestion.upsert(stations);
                }

                boolean complete = result.failed() == 0;
                if (complete) {
                    // Only now is it true that everything fetched was also stored, which is the
                    // precondition for an adapter to advance an incremental watermark.
                    adapters.forEach(SourceAdapter::commitProgress);
                } else {
                    log.warn("Sync run {} could not ingest {} of {} stations; incremental sources will "
                                    + "re-fetch their window rather than advance", runId, result.failed(), result.processed());
                }

                log.info("Sync run finished in {}: {} processed, {} created, {} updated, {} unchanged, {} failed",
                        Duration.ofNanos(System.nanoTime() - startedAt), result.processed(), result.created(),
                        result.updated(), result.unchanged(), result.failed());
                ingestion.finishRun(runId, complete
                        ? StationIngestionPort.RunStatus.SUCCEEDED
                        : StationIngestionPort.RunStatus.PARTIAL, result, null);
            } catch (RuntimeException exception) {
                // Swallowed on purpose: the scheduler must keep the next run scheduled after a bad upstream day.
                // The failure survives in master.sync_run, which is what the API reports on.
                log.error("Sync run failed after {}: {}", Duration.ofNanos(System.nanoTime() - startedAt),
                        exception.getMessage(), exception);
                ingestion.finishRun(runId, StationIngestionPort.RunStatus.FAILED, null, exception.toString());
            }
        }
    }
}
