package de.joinside.evmap_service.api.station;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface StationSourceRepository extends JpaRepository<StationSource, StationSourceId> {
    List<StationSource> findByStationIdOrderBySource(UUID stationId);
}
