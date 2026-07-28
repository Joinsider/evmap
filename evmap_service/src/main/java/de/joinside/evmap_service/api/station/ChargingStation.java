package de.joinside.evmap_service.api.station;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.UUID;

@Entity @Table(name = "charging_station", schema = "master")
class ChargingStation {
    @Id UUID id;
    @Column(name = "display_name") String displayName;
    String street; String city;
    @Column(name = "postal_code") String postalCode;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "country_code", columnDefinition = "char(2)", length = 2) String countryCode;
    @Column(name = "operator_name") String operatorName;
    double latitude; double longitude;
    @Column(name = "availability_status") String availabilityStatus;
    protected ChargingStation() { }
}
