package de.joinside.evmap_service.pricing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Providers are wired by component scan and {@code @ConfigurationProperties}, both invisible to unit tests —
 * the counterpart to {@code AvailabilityProviderRegistrationTests}. Extend it whenever a provider is added.
 */
class PriceProviderRegistrationTests {

    @Configuration
    @ComponentScan(basePackages = "de.joinside.evmap_service.pricing.mobidata")
    static class ProvidersOnly {
        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ProvidersOnly.class);

    @Test
    @DisplayName("the API deployable registers every price provider with a distinct source")
    void registersEveryProvider() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(PriceProvider.class).values())
                    .extracting(PriceProvider::source)
                    .containsExactlyInAnyOrder("MobiDataBW");
        });
    }

    @Test
    @DisplayName("the shipped defaults cover Germany and trust Allego's net prices")
    void bindsDefaults() {
        runner.run(context -> assertThat(context.getBeansOfType(PriceProvider.class).values())
                .allSatisfy(provider -> {
                    assertThat(provider.enabled()).isTrue();
                    assertThat(provider.covers("DE")).isTrue();
                    assertThat(provider.covers("CH")).isFalse();
                }));
    }

    @Test
    @DisplayName("the sync deployable registers none")
    void syncRegistersNone() {
        runner.withPropertyValues("evmap.pricing.enabled=false")
                .run(context -> assertThat(context.getBeansOfType(PriceProvider.class)).isEmpty());
    }
}
