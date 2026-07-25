package de.joinside.evmap_service.api.station;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ChargingStationRepository extends JpaRepository<ChargingStation, UUID> { }
