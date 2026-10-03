/**
 * Whether a published price is net or gross, where the feed does not say (ADR 0022, phases 5r and L6p): arithmetic
 * evidence ({@link de.joinside.evmap_service.vatbasis.VatEvidence}) and the hand-kept operator table
 * ({@link de.joinside.evmap_service.vatbasis.VatBasisTable}, {@code evmap.vat-basis}).
 * <p>
 * A neutral package like {@code mobilithek}: {@code pricing} (MobiData BW's tariffs, read in the API) and {@code sync}
 * (chargecloud's static Mobilithek feed, read in the sync) both use it, and it references neither, so the two stay
 * apart as {@code CLAUDE.md} requires.
 */
package de.joinside.evmap_service.vatbasis;
