package de.joinside.evmap_service.availability.mobidata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * The slice of MobiData BW's OCPI 3.0 payload this provider reads.
 * <p>
 * Everything else the endpoint returns — tariffs, opening times, capabilities, energy mix, the whole
 * static description of a location — is ignored: the station's own data comes from the registers via
 * {@code sync}, and taking a second copy of it from here would create a source of truth nobody
 * decided on.
 */
final class OcpdbResponses {

    private OcpdbResponses() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LocationPage(@JsonProperty("items") List<Location> items,
                        @JsonProperty("total_count") Integer totalCount,
                        @JsonProperty("next_offset") Integer nextOffset) {
    }

    /**
     * OCPI 3.0 inserts a charge station level between the location and its EVSEs, which OCPI 2.2 did
     * not have. It carries nothing this provider needs, but the EVSEs are nested inside it.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Location(@JsonProperty("id") String id,
                    @JsonProperty("source") String source,
                    @JsonProperty("charging_pool") List<ChargeStation> chargingPool) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChargeStation(@JsonProperty("evses") List<Evse> evses) {
    }

    /**
     * @param evseId            the published eMI3 identifier, the only thing joined on.
     * @param originalUid       the id as the upstream feed wrote it. Read as a second chance at the
     *                          identifier: OCPDB rewrites {@code evse_id} for some records while
     *                          leaving the original here, including the {@code BNETZA*<id>*<n>} form
     *                          it synthesizes for EVSEs derived from the register itself.
     * @param status            the OCPI status; see {@link OcpiStatus}.
     * @param statusLastUpdated when the status last changed. Distinct from {@code last_updated},
     *                          which tracks the static description and can be years older — using
     *                          that one would report a live status as ancient and get it hidden.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Evse(@JsonProperty("evse_id") String evseId,
                @JsonProperty("original_uid") String originalUid,
                @JsonProperty("status") String status,
                @JsonProperty("status_last_updated") Instant statusLastUpdated,
                @JsonProperty("last_updated") Instant lastUpdated) {
    }
}
