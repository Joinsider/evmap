package de.joinside.evmap_service.sync;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * The JSON columns of {@code master.charge_point_price} (migration 013): fees and energy windows as the API reads
 * them back. Absent fields are left out, never written as {@code null}.
 */
final class PriceJson {
    private static final JsonMapper JSON = JsonMapper.builder()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    private PriceJson() {
    }

    /** {@code null} for none, so the column stays empty rather than holding {@code []}. */
    static String timeFees(List<SourceStation.TimeFee> fees) {
        return fees.isEmpty() ? null : write(fees);
    }

    static String energyWindows(List<SourceStation.EnergyWindow> windows) {
        return windows.isEmpty() ? null : write(windows);
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // Records of strings and numbers always serialize; this would be a programming error.
            throw new IllegalStateException("price could not be written as JSON", e);
        }
    }
}
