package de.joinside.evmap_service.api.station;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The searchable list of charging networks the client's provider settings are built from.
 *
 * <p>Its own controller rather than a sub-resource of {@code /stations}: it answers "which networks
 * exist", which is a question about master data as a whole, not about one station or one viewport.
 */
@RestController
@RequestMapping("/api/v1/operators")
public class OperatorController {
    private final OperatorService operators;

    OperatorController(OperatorService operators) {
        this.operators = operators;
    }

    /**
     * @param query substring to match operator names against, case-insensitively. Absent or blank
     *              returns the operators with the most stations, which is what a freshly opened
     *              picker should show.
     */
    @GetMapping
    List<Operator> search(@RequestParam(required = false) String query, @RequestParam(defaultValue = "50") int limit) {
        return operators.search(query, limit);
    }

    /**
     * @param name         the operator name exactly as it is stored on the stations — there is no
     *                     operator id, so this string is also the key the client stores its
     *                     preference under.
     * @param stationCount how many stations carry that name; the list's ranking key and the only
     *                     hint the user gets about how much a choice affects the map.
     */
    public record Operator(String name, long stationCount) {
    }
}
