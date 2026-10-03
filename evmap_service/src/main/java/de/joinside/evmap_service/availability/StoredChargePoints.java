package de.joinside.evmap_service.availability;

import java.util.Collection;
import java.util.Set;

/**
 * The stored charge point inventory of whole countries, for providers that report how much of their feed it can
 * resolve. A diagnostic only: the join itself stays in {@link AvailabilityService}, per request and per station.
 */
public interface StoredChargePoints {

    /**
     * @param chargePoints every stored charge point in the countries, with or without an EVSE-ID
     * @param evseIds      the distinct normalized EVSE-IDs among them
     */
    record Inventory(long chargePoints, Set<String> evseIds) {
        public Inventory {
            evseIds = Set.copyOf(evseIds);
        }
    }

    Inventory inCountries(Collection<String> countryCodes);
}
