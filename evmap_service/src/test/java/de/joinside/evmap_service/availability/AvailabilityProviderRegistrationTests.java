package de.joinside.evmap_service.availability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Providers are wired by component scan and configured through {@code @ConfigurationProperties}
 * records, both invisible to unit tests. The counterpart to
 * {@code SourceAdapterRegistrationTests}, and for the same reason: a binding mistake would ship as an
 * API container that starts happily and answers UNKNOWN for every station in Europe.
 */
class AvailabilityProviderRegistrationTests {

    /** Scans the provider packages only, and supplies the one collaborator they need. */
    @Configuration
    @ComponentScan(basePackages = "de.joinside.evmap_service.availability.mobidata")
    static class ProvidersOnly {
        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ProvidersOnly.class);

    @Test
    @DisplayName("the API deployable registers every availability provider")
    void registersEveryProvider() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            // Extend this — and the source tokens below — whenever a provider is added.
            assertThat(context.getBeansOfType(AvailabilityProvider.class).values())
                    .extracting(provider -> provider.getClass().getSimpleName())
                    .containsExactlyInAnyOrder("MobiDataBwAvailabilityProvider");
        });
    }

    @Test
    @DisplayName("every provider names a distinct source, because logs and diagnostics key on it")
    void namesDistinctSources() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(AvailabilityProvider.class).values())
                    .extracting(AvailabilityProvider::source)
                    .containsExactlyInAnyOrder("MobiDataBW")
                    .allSatisfy(source -> assertThat(source).isNotBlank().hasSizeLessThanOrEqualTo(32));
        });
    }

    @Test
    @DisplayName("the sync deployable registers no provider, because it serves no requests")
    void registersNothingWhenDisabled() {
        runner.withPropertyValues("evmap.availability.enabled=false")
                .run(context -> assertThat(context.getBeansOfType(AvailabilityProvider.class)).isEmpty());
    }

    @Test
    @DisplayName("the shipped defaults bind, so a container without extra configuration answers live")
    void bindsDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            Object mobidata = propertiesBean(context, "MobiDataProperties");
            assertThat(mobidata).hasFieldOrPropertyWithValue("enabled", true)
                    .hasFieldOrPropertyWithValue("pageSize", 200);
            assertThat(mobidata).extracting("baseUrl").asString().contains("api.mobidata-bw.de");
            // An unbound List would be null here and fail at the first request rather than at startup.
            assertThat(mobidata).extracting("countryCodes").asInstanceOf(
                    org.assertj.core.api.InstanceOfAssertFactories.list(String.class)).contains("DE", "CH");
        });
    }

    /** Properties beans are named after their prefix and class, so they are looked up by suffix. */
    private static Object propertiesBean(ApplicationContext context, String simpleName) {
        for (String name : context.getBeanNamesForType(Object.class))
            if (name.endsWith(simpleName)) return context.getBean(name);
        throw new AssertionError("No configuration properties bean for " + simpleName);
    }
}
