package de.joinside.evmap_service.vatbasis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Period;
import java.util.List;

/**
 * The hand-kept table, shared by both readers of feeds without a VAT flag: MobiData BW's OCPI tariffs in the API and
 * chargecloud's static Mobilithek feed in the sync (ADR 0022, phases 5r and L6p).
 *
 * @param operators    operators whose publishing basis was checked by hand against their own price pages; matched
 *                     case-insensitively
 * @param recheckAfter how old a check may get before startup warns that it is due again
 */
@ConfigurationProperties("evmap.vat-basis")
public record VatBasisProperties(List<OperatorBasis> operators,
                                 @DefaultValue("P6M") Period recheckAfter) {

    public VatBasisProperties {
        operators = operators == null ? List.of() : List.copyOf(operators);
    }
}
