package de.joinside.evmap_service.availability.mobidata;

import de.joinside.evmap_service.availability.LiveAvailability;

/**
 * Maps OCPI's EVSE status onto the vocabulary the client understands.
 * <p>
 * OCPI distinguishes ten states; four of them answer the driver's question and the rest do not.
 * Split out of the provider so the mapping is testable without the network, and so the reasoning for
 * each collapse is written down once.
 */
final class OcpiStatus {

    private OcpiStatus() {
    }

    /**
     * @return the {@link LiveAvailability} token, or {@code null} when this status carries no live
     * information at all and the charge point should be treated as unreported rather than unknown.
     * The difference matters: a reported {@code UNKNOWN} still proves the source knows the charge
     * point exists, while {@code STATIC} means the record has no dynamic feed behind it and is a
     * static register copy — 88 % of OCPDB's EVSEs are that.
     */
    static String toLiveAvailability(String ocpiStatus) {
        if (ocpiStatus == null) return null;
        return switch (ocpiStatus.toUpperCase()) {
            case "AVAILABLE" -> LiveAvailability.AVAILABLE;
            // CHARGING is someone else's session; RESERVED is someone else's booking; BLOCKED is
            // OCPI's "not accessible because of a physical barrier, i.e. a car". All three mean the
            // driver cannot pull in, which is the only distinction worth carrying to a map pin.
            case "CHARGING", "RESERVED", "BLOCKED" -> LiveAvailability.OCCUPIED;
            // INOPERATIVE is the operator having switched it off, OUTOFORDER is it being broken.
            // Both are "do not drive here"; the reason is not something this app can act on.
            case "INOPERATIVE", "OUTOFORDER" -> LiveAvailability.OUT_OF_ORDER;
            // The source says it does not know. Passed through rather than dropped, because it is a
            // genuine answer about a charge point that exists.
            case "UNKNOWN" -> LiveAvailability.UNKNOWN;
            // PLANNED is not built yet and REMOVED is gone — neither describes something a driver
            // could arrive at, and reporting either would put a live badge on a station that has no
            // live feed. STATIC is OCPDB's marker for a record with no dynamic source behind it.
            case "PLANNED", "REMOVED", "STATIC" -> null;
            default -> null;
        };
    }
}
