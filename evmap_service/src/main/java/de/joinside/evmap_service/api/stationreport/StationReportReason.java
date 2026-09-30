package de.joinside.evmap_service.api.stationreport;

import java.util.Arrays;
import java.util.Locale;

/** What is wrong with a station: a closed set, so the queue can be grouped and the clients can label it (ADR 0021). */
enum StationReportReason {
    GONE, WRONG_POWER, WRONG_CONNECTOR, DEFECTIVE, OTHER;

    String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    static StationReportReason parse(String value) {
        return Arrays.stream(values()).filter(reason -> reason.token().equalsIgnoreCase(value == null ? "" : value.trim())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown report reason"));
    }
}
