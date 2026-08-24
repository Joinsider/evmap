package de.joinside.evmap_service.availability;

/**
 * Canonical values for live occupancy of a charge point.
 * <p>
 * Deliberately smaller than the vocabularies the sources use. OCPI alone distinguishes ten EVSE
 * states, most of which answer a question a driver is not asking: the difference between
 * {@code RESERVED} and {@code CHARGING} is that somebody else has the stall either way. Collapsing
 * them here rather than in each provider keeps the client from having to know any source's dialect.
 * <p>
 * Stored and transported as stable tokens, mirrored by the iOS {@code LiveAvailability} enum. An
 * unmapped token would surface raw to users, so adding one here means adding it in Swift too — the
 * same two-sided rule that governs {@link de.joinside.evmap_service.sync.ConnectorTypes} and
 * {@link de.joinside.evmap_service.sync.AvailabilityStatus}.
 */
public final class LiveAvailability {
    /** Free and usable now. */
    public static final String AVAILABLE = "AVAILABLE";
    /** In use, reserved, or physically blocked — someone else has it. */
    public static final String OCCUPIED = "OCCUPIED";
    /** Reported broken or switched off by the operator. */
    public static final String OUT_OF_ORDER = "OUT_OF_ORDER";
    /**
     * No live answer: no source covers this charge point, it publishes no EVSE-ID, the provider is
     * down, or the source itself says it does not know.
     * <p>
     * This is a first-class answer and must be rendered as one. The honest failure mode of a live
     * availability feature is "we don't know"; the dishonest one is "free".
     */
    public static final String UNKNOWN = "UNKNOWN";

    private LiveAvailability() {
    }
}
