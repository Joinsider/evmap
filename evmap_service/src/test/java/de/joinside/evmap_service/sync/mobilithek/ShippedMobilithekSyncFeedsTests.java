package de.joinside.evmap_service.sync.mobilithek;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The feed table as shipped in {@code application-sync.yaml}: a key that changes or repeats would re-create every
 * station of a feed under new ids, and a platform read before the operator it relays would take its charge points.
 */
class ShippedMobilithekSyncFeedsTests {

    @Configuration
    @EnableConfigurationProperties(MobilithekSyncProperties.class)
    static class PropertiesOnly {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class)
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=sync");

    @Test
    @DisplayName("every static offering is configured once, subscribed, and read before the platform relaying it")
    void bindsTheShippedTable() {
        runner.run(context -> {
            MobilithekSyncProperties properties = context.getBean(MobilithekSyncProperties.class);
            List<String> keys = properties.feeds().stream().map(MobilithekSyncProperties.Feed::key).toList();

            assertThat(properties.feeds()).hasSize(31).allSatisfy(feed -> {
                assertThat(feed.key()).matches("[a-z0-9]+");
                assertThat(feed.subscriptionId()).matches("\\d{19}");
                assertThat(feed.publisher()).isNotBlank();
            });
            assertThat(keys).doesNotHaveDuplicates();
            assertThat(properties.feeds()).extracting(MobilithekSyncProperties.Feed::subscriptionId).doesNotHaveDuplicates();
            assertThat(keys.indexOf("ladenetz")).isLessThan(keys.indexOf("eclearing"));
            assertThat(keys.indexOf("ladebusiness")).isLessThan(keys.indexOf("eclearing"));
            assertThat(properties.registerSource()).isEqualTo("BNetzA");
            assertThat(properties.countryCode()).isEqualTo("DE");
            assertThat(properties.subscribedFeeds()).hasSize(31);
        });
    }
}
