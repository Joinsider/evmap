package de.joinside.evmap_service.api.station;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity @Table(name = "charging_connector", schema = "master")
class ChargingConnector {
    @Id UUID id;
    @Column(name = "station_id") UUID stationId;
    @Column(name = "connector_type") String connectorType;
    @Column(name = "power_kw") BigDecimal powerKw;
    int quantity;
    protected ChargingConnector() { }
}
