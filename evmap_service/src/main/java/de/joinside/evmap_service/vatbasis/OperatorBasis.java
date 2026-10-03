package de.joinside.evmap_service.vatbasis;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * One checked operator.
 *
 * @param operator  the operator name exactly as the feeds write it — OCPDB on the location, chargecloud's Mobilithek
 *                  feed on the station (they agree: both are chargecloud's own export)
 * @param basis     {@code NET} or {@code GROSS}: how the operator's energy prices reach the feed
 * @param checkedOn when the official price page (or a recent third-party source) was compared
 * @param source    where: the page's URL, so the next check starts there
 */
public record OperatorBasis(String operator,
                            TableBasis basis,
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkedOn,
                            String source) {
}
