package de.joinside.evmap_service.vatbasis;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Arithmetic evidence for the VAT basis of a price whose feed does not state it (ADR 0022): a price is published to
 * whole cents gross, so a non-round value that lands on whole cents with VAT added was net, and one that lands on whole
 * cents with VAT taken off was gross. The same rule the IRVE recognizer applies to French prices.
 */
public final class VatEvidence {
    private VatEvidence() {
    }

    /**
     * The gross price when {@code net} is demonstrably net at {@code rate}, else {@code null}: more than two
     * decimals, and net × (1 + rate) on whole cents within the rounding of the decimals given.
     */
    public static BigDecimal provenGross(BigDecimal net, BigDecimal rate) {
        int decimals = net.stripTrailingZeros().scale();
        if (decimals <= 2) return null;
        BigDecimal gross = net.multiply(BigDecimal.ONE.add(rate));
        BigDecimal cents = gross.setScale(2, RoundingMode.HALF_UP);
        BigDecimal tolerance = new BigDecimal("0.6").movePointLeft(decimals);
        return gross.subtract(cents).abs().compareTo(tolerance) <= 0 ? cents : null;
    }

    /**
     * Whether {@code value} is demonstrably a gross price dressed as a net one: more than two decimals, and
     * value ÷ (1 + rate) on whole cents. Mainova publishes 0,6426, which is 0,54 × 1,19 — read as net it would
     * become 0,76 €. The mirror image of {@link #provenGross}.
     */
    public static boolean looksGross(BigDecimal value, BigDecimal rate) {
        int decimals = value.stripTrailingZeros().scale();
        if (decimals <= 2) return false;
        BigDecimal net = value.divide(BigDecimal.ONE.add(rate), 8, RoundingMode.HALF_UP);
        BigDecimal cents = net.setScale(2, RoundingMode.HALF_UP);
        BigDecimal tolerance = new BigDecimal("0.6").movePointLeft(decimals);
        return net.subtract(cents).abs().compareTo(tolerance) <= 0;
    }
}
