package de.joinside.evmap_service.availability.mobidata;

import de.joinside.evmap_service.availability.LiveAvailability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OcpiStatusTests {

    @Test
    @DisplayName("maps the states a driver can act on")
    void mapsActionableStates() {
        assertThat(OcpiStatus.toLiveAvailability("AVAILABLE")).isEqualTo(LiveAvailability.AVAILABLE);
        assertThat(OcpiStatus.toLiveAvailability("CHARGING")).isEqualTo(LiveAvailability.OCCUPIED);
        assertThat(OcpiStatus.toLiveAvailability("OUTOFORDER")).isEqualTo(LiveAvailability.OUT_OF_ORDER);
    }

    @Test
    @DisplayName("collapses the three ways of being taken onto OCCUPIED")
    void collapsesOccupancy() {
        // Someone else's session, someone else's booking, and OCPI's "blocked by a physical barrier,
        // i.e. a car" all mean the same thing to the driver deciding whether to drive there.
        assertThat(OcpiStatus.toLiveAvailability("CHARGING")).isEqualTo(LiveAvailability.OCCUPIED);
        assertThat(OcpiStatus.toLiveAvailability("RESERVED")).isEqualTo(LiveAvailability.OCCUPIED);
        assertThat(OcpiStatus.toLiveAvailability("BLOCKED")).isEqualTo(LiveAvailability.OCCUPIED);
    }

    @Test
    @DisplayName("passes a source's own UNKNOWN through as an answer")
    void keepsReportedUnknown() {
        // Distinct from "no source covers this": the feed knows the charge point and says it does not
        // know its state, which is still information about a charge point that exists.
        assertThat(OcpiStatus.toLiveAvailability("UNKNOWN")).isEqualTo(LiveAvailability.UNKNOWN);
    }

    @Test
    @DisplayName("reports nothing for records that carry no live information")
    void skipsNonLiveStates() {
        // STATIC is OCPDB's marker for a static register copy — 88 % of its EVSEs. Emitting these as
        // a status would put a live badge on stations with no dynamic feed behind them.
        assertThat(OcpiStatus.toLiveAvailability("STATIC")).isNull();
        assertThat(OcpiStatus.toLiveAvailability("PLANNED")).isNull();
        assertThat(OcpiStatus.toLiveAvailability("REMOVED")).isNull();
        assertThat(OcpiStatus.toLiveAvailability(null)).isNull();
    }

    @Test
    @DisplayName("ignores a state OCPI adds later rather than inventing a meaning for it")
    void ignoresUnknownTokens() {
        assertThat(OcpiStatus.toLiveAvailability("SOMETHING_NEW")).isNull();
    }
}
