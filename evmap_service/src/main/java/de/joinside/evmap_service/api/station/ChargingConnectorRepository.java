package de.joinside.evmap_service.api.station;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ChargingConnectorRepository extends JpaRepository<ChargingConnector, UUID> {
    List<ChargingConnector> findByStationId(UUID stationId);
}
