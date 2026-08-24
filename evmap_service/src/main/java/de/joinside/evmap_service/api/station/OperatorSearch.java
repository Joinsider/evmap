package de.joinside.evmap_service.api.station;

/**
 * A validated lookup against the operator directory: the {@code LIKE} pattern operator names are
 * matched against — {@code null} meaning "no name filter, return the most common ones" — and how
 * many rows to return.
 *
 * <p>Separate from {@link OperatorService} so the two things that are easy to get wrong here,
 * bounding the limit and escaping user input into a pattern, are testable without a database.
 */
record OperatorSearch(String pattern, int limit) {
    /**
     * Ceiling on returned rows. The directory is a picker, not a data export: past this the list
     * stops being scrollable and the client has to type instead.
     */
    static final int MAX_LIMIT = 200;

    static OperatorSearch of(String query, int limit) {
        if (limit < 1 || limit > MAX_LIMIT)
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        String trimmed = query == null ? "" : query.trim();
        return new OperatorSearch(trimmed.isEmpty() ? null : "%" + escape(trimmed) + "%", limit);
    }

    /**
     * Escapes the {@code LIKE} metacharacters, because everything the user typed is a literal.
     * Without this "50%" matches every operator whose name starts with "50", and an underscore
     * silently becomes "any character".
     *
     * <p>Backslash is PostgreSQL's default {@code LIKE} escape character; the query states it
     * explicitly all the same.
     */
    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 4);
        for (char character : value.toCharArray()) {
            if (character == '%' || character == '_' || character == '\\') escaped.append('\\');
            escaped.append(character);
        }
        return escaped.toString();
    }
}
