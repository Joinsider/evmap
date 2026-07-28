package de.joinside.evmap_service.sync.ocm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Wire records for the Open Charge Map API.
 * <p>
 * Property names are declared explicitly rather than derived: OCM serialises PascalCase, and the
 * alternative — the API's {@code camelcase=true} switch — would make the contract depend on a
 * transformation applied on their side. Only the fields the ingestion actually maps are modelled;
 * everything else is ignored, so a new upstream field cannot break a sync run.
 */
final class OcmResponses {

    private OcmResponses() {
    }

    /**
     * A charging site. Requested with {@code compact=true&verbose=false}, so reference data arrives as
     * bare ids ({@code OperatorID}, {@code ConnectionTypeID}) to be resolved against {@link ReferenceData}
     * — one small lookup per run instead of the same operator object repeated on every result.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Poi(@JsonProperty("ID") Long id,
               @JsonProperty("OperatorID") Integer operatorId,
               @JsonProperty("StatusTypeID") Integer statusTypeId,
               @JsonProperty("AddressInfo") AddressInfo addressInfo,
               @JsonProperty("Connections") List<Connection> connections,
               @JsonProperty("DateLastStatusUpdate") Instant dateLastStatusUpdate,
               @JsonProperty("DateLastVerified") Instant dateLastVerified,
               @JsonProperty("DateCreated") Instant dateCreated) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AddressInfo(@JsonProperty("Title") String title,
                       @JsonProperty("AddressLine1") String addressLine1,
                       @JsonProperty("Town") String town,
                       @JsonProperty("Postcode") String postcode,
                       @JsonProperty("Latitude") Double latitude,
                       @JsonProperty("Longitude") Double longitude) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Connection(@JsonProperty("ConnectionTypeID") Integer connectionTypeId,
                      @JsonProperty("PowerKW") BigDecimal powerKw,
                      @JsonProperty("Quantity") Integer quantity) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReferenceData(@JsonProperty("ConnectionTypes") List<Titled> connectionTypes,
                         @JsonProperty("Operators") List<Titled> operators,
                         @JsonProperty("StatusTypes") List<StatusType> statusTypes) {
    }

    /** The shape shared by every OCM lookup list entry that this ingestion needs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Titled(@JsonProperty("ID") Integer id, @JsonProperty("Title") String title) {
    }

    /**
     * OCM has many status types ("Operational", "Temporarily Unavailable", "Planned For Future Date",
     * …) but flags each as operational or not. That flag is the part worth mapping — the titles are
     * community-maintained free text and would drag their wording into our schema.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record StatusType(@JsonProperty("ID") Integer id,
                      @JsonProperty("Title") String title,
                      @JsonProperty("IsOperational") Boolean isOperational) {
    }
}
