package de.joinside.evmap_service.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The adapters are wired by component scan and configured through {@code @ConfigurationProperties}
 * records. Both are invisible to unit tests, and a binding mistake would surface only as a sync
 * container that starts happily and ingests nothing.
 */
class SourceAdapterRegistrationTests {

    /**
     * Scans the adapter packages only, and supplies the one collaborator they need. Providing the
     * builder directly rather than pulling in HTTP auto-configuration keeps this test about wiring.
     */
    @Configuration
    @ComponentScan(basePackages = {"de.joinside.evmap_service.sync.bnetza", "de.joinside.evmap_service.sync.irve",
            "de.joinside.evmap_service.sync.ocm"})
    static class AdaptersOnly {
        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }

        /** Stands in for the repository, which would drag in a database this test does not need. */
        @Bean
        SyncStateStore syncStateStore() {
            return new SyncStateStore() {
                @Override
                public java.util.Optional<java.time.Instant> watermark(String source, String scope) {
                    return java.util.Optional.empty();
                }

                @Override
                public void recordWatermark(String source, String scope, java.time.Instant watermark) {
                }
            };
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AdaptersOnly.class);

    @Test
    @DisplayName("the sync deployable registers every source adapter")
    void registersEveryAdapter() {
        runner.withPropertyValues("evmap.sync.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            // Extend this — and the source tokens below — whenever a source is added. The whole point
            // of asserting the full set is that a forgotten adapter fails here rather than shipping as
            // a container that starts happily and ingests one country less than it should.
            assertThat(context.getBeansOfType(SourceAdapter.class).values())
                    .extracting(adapter -> adapter.getClass().getSimpleName())
                    .containsExactlyInAnyOrder("BnetzaCsvSourceAdapter", "IrveCsvSourceAdapter",
                            "OpenChargeMapSourceAdapter");
        });
    }

    @Test
    @DisplayName("every adapter names a distinct source, because the run and the merge key on it")
    void namesDistinctSources() {
        runner.withPropertyValues("evmap.sync.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(SourceAdapter.class).values())
                    .extracting(SourceAdapter::source)
                    // Two adapters sharing a token would silently overwrite each other's stations and
                    // each other's incremental watermarks.
                    .containsExactlyInAnyOrder("BNetzA", "IRVE", "OCM")
                    // master.charging_station_source.source is VARCHAR(32).
                    .allSatisfy(source -> assertThat(source).isNotBlank().hasSizeLessThanOrEqualTo(32));
        });
    }

    @Test
    @DisplayName("the API deployable registers no adapter, because it must never write master data")
    void registersNothingWithoutSyncEnabled() {
        runner.run(context -> assertThat(context.getBeansOfType(SourceAdapter.class)).isEmpty());
    }

    @Test
    @DisplayName("the shipped defaults bind, so a container without extra configuration still syncs")
    void bindsDefaults() {
        runner.withPropertyValues("evmap.sync.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            // Reading the records back proves @DefaultValue binding actually ran; an unbound List or
            // Duration would otherwise be null and fail at the first sync rather than at startup.
            Object ocm = propertiesBean(context.getBeanNamesForType(Object.class), context, "OpenChargeMapProperties");
            assertThat(ocm).hasFieldOrPropertyWithValue("pageSize", 500)
                    .hasFieldOrPropertyWithValue("openDataOnly", true)
                    .hasFieldOrPropertyWithValue("baseUrl", "https://api.openchargemap.io/v3");
            assertThat(ocm).extracting("countryCodes").asInstanceOf(
                            org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                    .contains("DE", "AT", "CH");

            Object bnetza = propertiesBean(context.getBeanNamesForType(Object.class), context, "BnetzaProperties");
            assertThat(bnetza).hasFieldOrPropertyWithValue("enabled", true);
            assertThat(bnetza).extracting("indexUrl").asString().contains("Ladesaeulenkarte");

            Object irve = propertiesBean(context.getBeanNamesForType(Object.class), context, "IrveProperties");
            assertThat(irve).hasFieldOrPropertyWithValue("enabled", true);
            assertThat(irve).extracting("csvUrl").asString().contains("data.gouv.fr");
        });
    }

    /** Properties beans are named after their prefix and class, so they are looked up by suffix. */
    private static Object propertiesBean(String[] beanNames,
                                         org.springframework.context.ApplicationContext context,
                                         String simpleName) {
        for (String name : beanNames) if (name.endsWith(simpleName)) return context.getBean(name);
        throw new AssertionError("No configuration properties bean for " + simpleName);
    }
}
