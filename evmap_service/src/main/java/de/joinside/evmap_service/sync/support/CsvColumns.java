package de.joinside.evmap_service.sync.support;

import org.apache.commons.csv.CSVRecord;

import java.util.HashMap;
import java.util.Map;

/**
 * Reads CSV fields by column name, tolerating a header that is not quite what was expected.
 * <p>
 * Both registers ingested from CSV are published under a legally fixed schema and both still drift:
 * the Bundesnetzagentur file carries a byte-order mark on its first column and a preamble above the
 * header, the French consolidation exists in several schema versions at once. Addressing columns by
 * name and returning {@code ""} for one that is absent means a source that gained or lost a column
 * degrades to a missing field rather than to an exception on every row.
 * <p>
 * Names are matched after trimming and stripping the BOM, because an invisible mark on
 * the register's {@code Ladeeinrichtungs-ID} header is otherwise a lookup that fails for no visible
 * reason.
 */
public final class CsvColumns {
    /** Escaped rather than literal: the mark is invisible in an editor and easy to lose in a merge. */
    private static final String BOM = "\uFEFF";

    private final Map<String, Integer> indices;

    private CsvColumns(Map<String, Integer> indices) {
        this.indices = indices;
    }

    /**
     * Indexes a header row. A name occurring twice keeps its first position — repeated column groups
     * are addressed by their numbered names, so a genuine duplicate is a defect in the file and the
     * leftmost one is the one the publisher meant.
     */
    public static CsvColumns of(CSVRecord header) {
        Map<String, Integer> indices = new HashMap<>();
        for (int i = 0; i < header.size(); i++) indices.putIfAbsent(clean(header.get(i)), i);
        return new CsvColumns(indices);
    }

    /** @return the trimmed value, or {@code ""} when the column or the field is absent */
    public String get(CSVRecord row, String column) {
        Integer index = indices.get(column);
        if (index == null || index >= row.size()) return "";
        return clean(row.get(index));
    }

    public boolean has(String column) {
        return indices.containsKey(column);
    }

    public int size() {
        return indices.size();
    }

    /** Trims and strips the byte-order mark a file's first field may carry. */
    public static String clean(String value) {
        return value == null ? "" : value.replace(BOM, "").trim();
    }
}
