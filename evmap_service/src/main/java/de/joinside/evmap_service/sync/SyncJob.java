package de.joinside.evmap_service.sync;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "evmap.sync.enabled", havingValue = "true")
class SyncJob {
    private final List<SourceAdapter> adapters;
    private final StationIngestionPort ingestion;

    SyncJob(List<SourceAdapter> adapters, StationIngestionPort ingestion) {
        this.adapters = adapters;
        this.ingestion = ingestion;
    }

    @Scheduled(fixedDelayString = "${evmap.sync.fixed-delay}")
    void synchronize() {
        ingestion.upsert(adapters.stream().flatMap(SourceAdapter::fetchStations));
    }
}
