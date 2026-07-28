package de.joinside.evmap_service.sync;

/**
 * Canonical values for {@code master.charging_station.availability_status}.
 * <p>
 * This is <em>reported</em> availability from the source's own records, not live occupancy — real-time
 * availability is an explicit v1 non-goal (Lastenheft §10). What it does deliver is the field
 * Lastenheft §3 asks to be prepared: BNetzA distinguishes {@code In Betrieb} from {@code In Wartung},
 * and Open Charge Map's status types carry an {@code IsOperational} flag.
 * <p>
 * Stored as stable tokens rather than source wording, because the values cross a language boundary:
 * backend data is monolingual by design, while the iOS client has to render them in German and
 * English. The client maps these to localized strings ({@code station.availability.*}); an unmapped
 * token would surface raw, so adding one here means adding it in Swift too.
 */
public final class AvailabilityStatus {
    /** In service according to the source. */
    public static final String OPERATIONAL = "OPERATIONAL";
    /** Installed and registered, temporarily out of service — BNetzA's {@code In Wartung}. */
    public static final String MAINTENANCE = "MAINTENANCE";
    /** Not usable, without the source saying it is temporary — OCM's non-operational status types. */
    public static final String OUT_OF_SERVICE = "OUT_OF_SERVICE";

    private AvailabilityStatus() {
    }
}
