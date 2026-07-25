package de.joinside.evmap_service.api.station;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity @Table(name = "station_source", schema = "master") @IdClass(StationSourceId.class)
class StationSource {
    @Id String source;
    @Id @Column(name = "source_station_id") String sourceStationId;
    @Column(name = "station_id") UUID stationId;
    protected StationSource() { }
}
