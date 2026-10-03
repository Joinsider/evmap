package de.joinside.evmap_service.sync.mobilithek;

/**
 * Static AFIR packages in the dialects seen on 2026-10-03, cut down to what the mapper reads. Shapes and values are
 * the publishers' own; only the number of sites is reduced.
 */
final class AfirFixtures {
    private AfirFixtures() {
    }

    /**
     * EWE: location and address on the site, the EVSE-ID as a typed {@code externalIdentifier}, the register's station
     * id on the station, a code for a name and an operator id where the name should be. A second station shares the
     * site's position; a third site lies in Austria, a fourth has no position at all.
     */
    static final String EWE_JSON = """
            {"payload": {"aegiEnergyInfrastructureTablePublication": {"energyInfrastructureTable": [{
              "energyInfrastructureSite": [
                {"idG": "site-1", "name": {"values": [{"lang": "de", "value": "000501"}]},
                 "locationReference": {"locAreaLocation": {
                   "coordinatesForDisplay": {"latitude": "53.1464157", "longitude": "8.2167098"},
                   "locLocationExtensionG": {"FacilityLocation": {"address": {
                     "postcode": "26123", "city": {"values": [{"lang": "de", "value": "Oldenburg (Oldb.)"}]},
                     "countryCode": "DE",
                     "addressLine": [
                       {"order": "0", "type": {"value": "street"}, "text": {"values": [{"lang": "de", "value": "Donnerschweer Straße"}]}},
                       {"order": "1", "type": {"value": "houseNumber"}, "text": {"values": [{"lang": "de", "value": "22-26"}]}}]}}}}},
                 "operator": {"afacAnOrganisation": {"name": {"values": [{"lang": "de", "value": "DE*EWE"}]},
                                                     "legalName": {"values": [{"lang": "de", "value": "EWE Go GmbH"}]}}},
                 "energyInfrastructureStation": [
                   {"idG": "station-1", "lastUpdated": "2026-09-16T06:00:17.602Z",
                    "externalIdentifier": [{"identifier": "1115028",
                      "typeOfIdentifier": {"value": "extendedG", "extendedValueG": "stationIdBNetzA"}}],
                    "refillPoint": [
                      {"aegiElectricChargingPoint": {"idG": "88ee07d2-cda9-4c80-88aa-8cf4aa1251d1",
                        "availableChargingPower": [22000],
                        "externalIdentifier": [{"identifier": "DE*EWE*E000501S04*01",
                          "typeOfIdentifier": {"value": "extendedG", "extendedValueG": "evseId"}}],
                        "connector": [{"connectorType": {"value": "iec62196T2"}, "maxPowerAtSocket": "22000"}]}},
                      {"aegiElectricChargingPoint": {"idG": "079d4f11-78c1-478d-8d3f-d4c58f5f6fcc",
                        "externalIdentifier": [{"identifier": "DE*EWE*E000501S04*02",
                          "typeOfIdentifier": {"value": "extendedG", "extendedValueG": "evseId"}}],
                        "connector": [{"connectorType": {"value": "iec62196T2"}, "maxPowerAtSocket": "22000"}]}}]},
                   {"idG": "station-2",
                    "refillPoint": [{"aegiElectricChargingPoint": {"idG": "b40f7571",
                        "externalIdentifier": [{"identifier": "DE*EWE*E000501S05*01",
                          "typeOfIdentifier": {"value": "extendedG", "extendedValueG": "evseId"}}],
                        "connector": [{"connectorType": {"value": "iec62196T2COMBO"}, "maxPowerAtSocket": "150000"},
                                      {"connectorType": {"value": "iec62196T2COMBO"}, "maxPowerAtSocket": "150000"}]}}]}]},
                {"idG": "site-at",
                 "locationReference": {"locPointLocation": {"coordinatesForDisplay": {"latitude": 47.26, "longitude": 11.39},
                   "locLocationExtensionG": {"facilityLocation": {"address": {"countryCode": "AT"}}}}},
                 "energyInfrastructureStation": {"idG": "station-at",
                   "refillPoint": {"aegiElectricChargingPoint": {"idG": "AT*EWE*E1",
                     "connector": {"connectorType": {"value": "iec62196T2"}}}}}},
                {"idG": "site-nowhere",
                 "energyInfrastructureStation": [{"idG": "station-nowhere",
                   "refillPoint": [{"aegiElectricChargingPoint": {"idG": "DE*EWE*E9"}}]}]}]}]}}}
            """;

    /**
     * EnBW and its kin: location only on the station, the EVSE-ID as the refill point's {@code idG}, the register's
     * station id typed {@code operatorIdBNetzA}, no name. VW writes the station's name into the operator's
     * {@code legalName}; GP JOULE wraps its EVSE-ID; one refill point repeats an EVSE-ID an earlier feed carried.
     */
    static final String ENBW_JSON = """
            {"payload": {"aegiEnergyInfrastructureTablePublication": {"energyInfrastructureTable": [{
              "energyInfrastructureSite": [
                {"idG": "800030182", "lastUpdated": "2026-10-02T22:00:00Z",
                 "operator": {"afacAnOrganisation": {"name": {"values": [{"lang": "de", "value": "ENBW"}]}}},
                 "energyInfrastructureStation": [{"idG": "13529",
                   "externalIdentifier": [{"identifier": "1121150",
                     "typeOfIdentifier": {"value": "extendedG", "extendedValueG": "operatorIdBNetzA"}},
                                          {"identifier": "not-a-number",
                     "typeOfIdentifier": {"value": "extendedG", "extendedValueG": "stationIdBNetzA"}}],
                   "locationReference": {"locPointLocation": {
                     "pointByCoordinates": {"pointCoordinates": {"latitude": 48.718544, "longitude": 9.611829}},
                     "locLocationExtensionG": {"facilityLocation": {"address": {"countryCode": "de",
                       "addressLine": [{"type": {"value": "street"}, "text": {"values": [{"value": "Siemensstraße"}]}}]}}}}},
                   "refillPoint": [
                     {"aegiElectricChargingPoint": {"idG": "DE*EBW*E914081*1",
                       "connector": [{"connectorType": {"value": "iec62196T2COMBO"}, "maxPowerAtSocket": 150000.0},
                                     {"connectorType": {"value": "unknown"}}]}},
                     {"aegiElectricChargingPoint": {"idG": "DE*EWE*E000501S04*01",
                       "connector": [{"connectorType": {"value": "iec62196T2"}}]}}]},
                  {"idG": "elli-3", "name": {"values": [{"lang": "de", "value": "Elli Box 3 Bremen"}]},
                   "operator": {"afacAnOrganisation": {"legalName": {"values": [{"lang": "de", "value": "Elli Box 3 Bremen"}]}}},
                   "locationReference": {"locPointLocation": {"coordinatesForDisplay": {"latitude": "53.04", "longitude": "8.87"}}},
                   "refillPoint": [{"aegiElectricChargingPoint": {"idG": "cp-DE*CNT*EP90046*002*1-1",
                     "connector": [{"connectorType": {"value": "domesticF"}, "maxPowerAtSocket": 0}]}},
                                   {"aegiElectricChargingPoint": {"idG": "54bcecea3be75033a5060f0c75b86a21"}}]}]}]}]}}}
            """;

    /**
     * ladenetz.de: XML, with namespace prefixes, an {@code xsi:type} on every refill point, plain-text identifiers,
     * and an operator name that is the operator's id.
     */
    static final String LADENETZ_XML = """
            ﻿<?xml version="1.0" encoding="UTF-8"?>
            <ns2:messageContainer xmlns="http://datex2.eu/schema/3/common" xmlns:ns2="http://datex2.eu/schema/3/messageContainer"
                xmlns:ns6="http://datex2.eu/schema/3/facilities" xmlns:ns9="http://datex2.eu/schema/3/locationReferencing"
                xmlns:ns10="http://datex2.eu/schema/3/locationExtension" xmlns:ns11="http://datex2.eu/schema/3/energyInfrastructure"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <ns2:payload><ns11:energyInfrastructureTable id="DE" version="1">
                <ns11:energyInfrastructureSite id="DESTAS0187" version="2026-10-01">
                  <ns6:name><values><value lang="de">AC-Süd - Hangeweiher</value></values></ns6:name>
                  <ns6:locationReference xsi:type="ns9:PointLocation">
                    <ns9:_locationReferenceExtension><ns9:facilityLocation><ns10:address>
                      <ns10:postcode>52074</ns10:postcode>
                      <ns10:city><values><value lang="de">Aachen</value></values></ns10:city>
                      <ns10:countryCode>DE</ns10:countryCode>
                      <ns10:addressLine order="1"><ns10:type>street</ns10:type>
                        <ns10:text><values><value lang="de">Kaiser-Friedrich-Allee</value></values></ns10:text></ns10:addressLine>
                      <ns10:addressLine order="2"><ns10:type>houseNumber</ns10:type>
                        <ns10:text><values><value lang="de">5</value></values></ns10:text></ns10:addressLine>
                    </ns10:address></ns9:facilityLocation></ns9:_locationReferenceExtension>
                    <ns9:coordinatesForDisplay><ns9:latitude>50.758728</ns9:latitude><ns9:longitude>6.0749</ns9:longitude></ns9:coordinatesForDisplay>
                  </ns6:locationReference>
                  <ns6:operator id="DESTAE018701" xsi:type="ns6:OrganisationSpecification">
                    <ns6:name><values><value lang="en">DESTA</value></values></ns6:name>
                  </ns6:operator>
                  <ns11:energyInfrastructureStation id="DESTAS0187" version="2026-10-01">
                    <ns6:externalIdentifier>DESTAS0187</ns6:externalIdentifier>
                    <ns11:refillPoint id="DESTAE018701" version="2026-10-01" xsi:type="ns11:ElectricChargingPoint">
                      <ns6:externalIdentifier>DESTAE018701</ns6:externalIdentifier>
                      <ns11:connector><ns11:connectorType>iec62196T2</ns11:connectorType>
                        <ns11:maxPowerAtSocket>22000.0</ns11:maxPowerAtSocket></ns11:connector>
                    </ns11:refillPoint>
                    <ns11:refillPoint id="DESTAE018702" version="2026-10-01" xsi:type="ns11:ElectricChargingPoint">
                      <ns6:externalIdentifier>
                        <ns6:identifier>DE*STA*E018702</ns6:identifier>
                        <ns6:typeOfIdentifier>extendedG</ns6:typeOfIdentifier>
                        <ns6:typeOfIdentifierExtension><ns6:extendedValueG>evseId</ns6:extendedValueG></ns6:typeOfIdentifierExtension>
                      </ns6:externalIdentifier>
                      <ns11:connector><ns11:connectorType>iec62196T2</ns11:connectorType>
                        <ns11:maxPowerAtSocket>22000.0</ns11:maxPowerAtSocket></ns11:connector>
                    </ns11:refillPoint>
                  </ns11:energyInfrastructureStation>
                </ns11:energyInfrastructureSite>
              </ns11:energyInfrastructureTable></ns2:payload>
            </ns2:messageContainer>
            """;
}
