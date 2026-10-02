package de.joinside.evmap_service.availability.mobilithek;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AfirStaticIdParserTests {

    /** Shaped after eRound's and Wirelane's static feeds: one EVSE-ID per refill point, typed as an extension. */
    static final String STATIC_JSON = """
            {"messageContainer": {"payload": [{"aegiEnergyInfrastructureTablePublication": {
              "energyInfrastructureTable": [{"energyInfrastructureSite": [{"energyInfrastructureStation": [{
                "refillPoint": [
                  {"aegiElectricChargingPoint": {
                    "idG": "16bc39bf627fba518275f6b6c238f17e", "versionG": "1",
                    "externalIdentifier": [{"identifier": "DE*WWL*E7110104",
                      "typeOfIdentifier": {"value": "extendedG", "extendedValueG": "evseId"}}],
                    "electricEnergy": [{"energyRate": [{"energyPrice": [{"value": 0.6375, "taxIncluded": true}]}]}]}},
                  {"aegiElectricChargingPoint": {
                    "idG": "54bcecea-3be7-5033-a506-0f0c75b86a21",
                    "externalIdentifier": [
                      {"identifier": "4711", "typeOfIdentifier": {"value": "other"}},
                      {"identifier": "DE*WLN*EP002898"}]}},
                  {"aegiElectricChargingPoint": {
                    "idG": "no-evse-id-here",
                    "externalIdentifier": {"identifier": "ae0b0ee4-f1d7-5222-a96c-81937bc19220"}}},
                  {"aegiElectricChargingPoint": {
                    "externalIdentifier": [{"identifier": "DE*ABC*E1", "typeOfIdentifier": {"extendedValueG": "evseId"}}]}}
                ]}]}]}]}}]}}
            """;

    private static Map<String, String> parse(String body) throws IOException {
        return AfirStaticIdParser.parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("maps each internal refill point id onto its EVSE-ID, both normalized")
    void readsJson() throws IOException {
        Map<String, String> evseIds = parse(STATIC_JSON);

        assertThat(evseIds).containsOnly(
                Map.entry("16BC39BF627FBA518275F6B6C238F17E", "DEWWLE7110104"),
                // Untyped, but the only identifier with the shape of an EVSE-ID; the typed "other" is not one.
                Map.entry("54BCECEA3BE75033A5060F0C75B86A21", "DEWLNEP002898"));
    }

    @Test
    @DisplayName("an identifier typed evseId wins over one that merely looks like an EVSE-ID")
    void typedIdentifierWins() throws IOException {
        Map<String, String> evseIds = parse("""
                {"aegiElectricChargingPoint": {"idG": "cp-1", "externalIdentifier": [
                  {"identifier": "DE*XYZ*E9"},
                  {"identifier": "DE*ABC*E1", "typeOfIdentifier": "evseId"}]}}
                """);

        assertThat(evseIds).containsExactly(Map.entry("CP1", "DEABCE1"));
    }

    @Test
    @DisplayName("reads the XML syntax too, whatever the prefixes and however the type is extended")
    void readsXml() throws IOException {
        Map<String, String> evseIds = parse("""
                <con:messageContainer xmlns:con="c" xmlns:egi="g" xmlns:f="f" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                  <egi:refillPoint xsi:type="egi:ElectricChargingPoint" id="16bc39bf627fba518275f6b6c238f17e" version="1">
                    <egi:externalIdentifier>
                      <f:identifier>DE*WWL*E7110104</f:identifier>
                      <f:typeOfIdentifier>extendedG</f:typeOfIdentifier>
                      <f:_typeOfIdentifierExtension><f:typeOfIdentifierExtended>evseId</f:typeOfIdentifierExtended></f:_typeOfIdentifierExtension>
                    </egi:externalIdentifier>
                    <egi:connector><egi:connectorType>iec62196T2</egi:connectorType></egi:connector>
                  </egi:refillPoint>
                  <egi:refillPoint id="internal-2">
                    <egi:externalIdentifier><f:identifier>4711-A</f:identifier></egi:externalIdentifier>
                  </egi:refillPoint>
                </con:messageContainer>
                """);

        assertThat(evseIds).containsExactly(Map.entry("16BC39BF627FBA518275F6B6C238F17E", "DEWWLE7110104"));
    }

    @Test
    @DisplayName("an XML static package declaring external entities is refused")
    void refusesExternalEntities() {
        assertThatThrownBy(() -> parse("""
                <?xml version="1.0"?>
                <!DOCTYPE c [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <c><refillPoint id="x"><externalIdentifier><identifier>&secret;</identifier></externalIdentifier></refillPoint></c>
                """)).isInstanceOf(IOException.class);
    }
}
