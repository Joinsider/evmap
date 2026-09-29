package de.joinside.evmap_service.api.moderation;

import java.util.Arrays;
import java.util.Locale;

/** Why a comment was reported: a closed set, so the queue can be grouped and the clients can label it (ADR 0020). */
enum ReportReason {
    SPAM, OFFENSIVE, WRONG, OTHER;

    String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    static ReportReason parse(String value) {
        return Arrays.stream(values()).filter(reason -> reason.token().equalsIgnoreCase(value == null ? "" : value.trim())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown report reason"));
    }
}
