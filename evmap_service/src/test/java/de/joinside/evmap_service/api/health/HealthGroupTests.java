package de.joinside.evmap_service.api.health;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertySourceFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The three health URLs carry three different meanings (ADR 0004, ADR 0019), and that meaning lives
 * in {@code application.yaml}'s status order and HTTP mapping, not in the indicators. So this runs the
 * real actuator configuration with the two ingestion indicators pinned to a state, and asserts what
 * an external monitor would actually see.
 * <p>
 * The test {@code application.yaml} shadows the main one on the classpath, so the main file's
 * {@code management.*} keys are loaded explicitly — and only those, since the rest would replace the
 * test database with the production one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "file:src/main/resources/application.yaml", factory = HealthGroupTests.ManagementKeysOnly.class)
class HealthGroupTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IngestionHealthIndicator ingestion;

    @MockitoBean
    private IngestionCompletenessHealthIndicator completeness;

    @BeforeEach
    void allHealthy() {
        pin(ingestion, Health.up().build());
        pin(completeness, Health.up().build());
    }

    @Test
    @DisplayName("a partial sync run alerts only on the sync group")
    void partialRunAlertsOnlyOnTheSyncGroup() throws Exception {
        pin(completeness, Health.status(IngestionCompletenessHealthIndicator.INCOMPLETE).build());

        mockMvc.perform(get("/actuator/health/sync"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("INCOMPLETE"));
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/container")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a failed sync run alerts on the root and the sync group, never on the container group")
    void failedRunAlertsOnRootAndSyncGroup() throws Exception {
        pin(ingestion, Health.down().build());

        mockMvc.perform(get("/actuator/health/sync"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));
        mockMvc.perform(get("/actuator/health")).andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/actuator/health/container")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a failed run outranks a partial one in the sync group")
    void downOutranksIncomplete() throws Exception {
        pin(ingestion, Health.down().build());
        pin(completeness, Health.status(IngestionCompletenessHealthIndicator.INCOMPLETE).build());

        mockMvc.perform(get("/actuator/health/sync")).andExpect(jsonPath("$.status").value("DOWN"));
    }

    @Test
    @DisplayName("everything healthy answers 200 on all three URLs")
    void allHealthyIsOkEverywhere() throws Exception {
        mockMvc.perform(get("/actuator/health/sync")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/container")).andExpect(status().isOk());
    }

    static class ManagementKeysOnly implements PropertySourceFactory {
        @Override
        public PropertySource<?> createPropertySource(String name, EncodedResource resource) throws IOException {
            Map<String, Object> management = new LinkedHashMap<>();
            for (PropertySource<?> document : new YamlPropertySourceLoader().load("main-application", resource.getResource())) {
                if (!(document instanceof EnumerablePropertySource<?> enumerable)) continue;
                for (String key : enumerable.getPropertyNames())
                    if (key.startsWith("management.")) management.put(key, enumerable.getProperty(key));
            }
            return new MapPropertySource("main-application-management", management);
        }
    }

    /** The endpoint may call either overload; a mock would answer null to the one left unstubbed. */
    private static void pin(HealthIndicator indicator, Health health) {
        when(indicator.health()).thenReturn(health);
        when(indicator.health(anyBoolean())).thenReturn(health);
    }
}
