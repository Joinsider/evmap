package de.joinside.evmap_service.pricing.mobidata;

import de.joinside.evmap_service.pricing.mobidata.MobiDataPricingProperties.OperatorBasis;
import de.joinside.evmap_service.pricing.mobidata.OcpiTariffs.TableBasis;

import java.time.LocalDate;
import java.time.Period;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Operators whose publishing basis — net or gross — was checked by hand against their own price pages (ADR 0022,
 * phase 5r), because OCPDB drops the flag that would say so (binary-butterfly/ocpdb#278).
 * <p>
 * An entry is only as good as the day it was checked. When the feed later contradicts it — a tariff of an
 * operator listed as gross that the arithmetic proves net, or the reverse — the entry is {@linkplain #suspend
 * suspended} for the rest of the process's life and the operator's prices disappear until someone checks again.
 */
final class VatBasisTable {
    private final Map<String, OperatorBasis> entries;
    private final Set<String> suspended = ConcurrentHashMap.newKeySet();

    private VatBasisTable(Map<String, OperatorBasis> entries) {
        this.entries = Map.copyOf(entries);
    }

    /**
     * @throws IllegalArgumentException for an entry without a name, basis or check date, or an operator listed
     *                                  twice — a table that says two things about one operator says nothing
     */
    static VatBasisTable of(Collection<OperatorBasis> operators) {
        Map<String, OperatorBasis> entries = new HashMap<>();
        if (operators != null) for (OperatorBasis entry : operators) {
            if (entry == null || entry.operator() == null || entry.operator().isBlank())
                throw new IllegalArgumentException("evmap.pricing.mobidata.vat-basis: an entry has no operator");
            if (entry.basis() == null || entry.basis() == TableBasis.UNCHECKED || entry.checkedOn() == null)
                throw new IllegalArgumentException("evmap.pricing.mobidata.vat-basis: " + entry.operator()
                        + " needs a basis (NET or GROSS) and a checked-on date");
            if (entries.putIfAbsent(key(entry.operator()), entry) != null)
                throw new IllegalArgumentException("evmap.pricing.mobidata.vat-basis: " + entry.operator() + " is listed twice");
        }
        return new VatBasisTable(entries);
    }

    static String key(String operatorName) {
        return operatorName.trim().toLowerCase(Locale.ROOT);
    }

    /** What the table says about an operator; {@code UNCHECKED} when it is not listed or its entry is suspended. */
    TableBasis basisOf(String operatorName) {
        if (operatorName == null) return TableBasis.UNCHECKED;
        String key = key(operatorName);
        OperatorBasis entry = entries.get(key);
        return entry == null || suspended.contains(key) ? TableBasis.UNCHECKED : entry.basis();
    }

    /** Suspends a listed operator's entry; {@code true} only the first time, so the caller warns once. */
    boolean suspend(String operatorName) {
        if (operatorName == null) return false;
        String key = key(operatorName);
        return entries.containsKey(key) && suspended.add(key);
    }

    /** The entries checked longer ago than {@code after}, oldest first. */
    List<OperatorBasis> dueForRecheck(LocalDate today, Period after) {
        LocalDate cutoff = today.minus(after);
        return entries.values().stream()
                .filter(entry -> entry.checkedOn().isBefore(cutoff))
                .sorted(java.util.Comparator.comparing(OperatorBasis::checkedOn))
                .toList();
    }
}
