package de.joinside.evmap_service.availability.mobilithek;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The feed table ships in {@code application.yaml}; a typo there would credit a charge point to nobody, or bind no
 * feeds at all and leave Germany to MobiData BW without a word. Binds the real file (the test classpath has its own
 * {@code application.yaml}).
 */
class ShippedMobilithekFeedsTests {

    private static MobilithekProperties shipped() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yaml", new FileSystemResource("src/main/resources/application.yaml"));
        // Placeholders such as ${MOBILITHEK_KEYSTORE_PATH:} resolve to their defaults, as without an environment.
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources))
                .bindOrCreate("evmap.availability.mobilithek", MobilithekProperties.class);
    }

    @Test
    @DisplayName("every dynamic AFIR offering is listed once, with publisher, licence and its catalogue page")
    void everyFeedIsComplete() throws IOException {
        MobilithekProperties properties = shipped();

        assertThat(properties.feeds()).hasSizeGreaterThanOrEqualTo(28).allSatisfy(feed -> {
            assertThat(feed.publisher()).isNotBlank();
            assertThat(feed.licence()).isNotBlank();
            assertThat(feed.url()).matches("https://mobilithek\\.info/offers/\\d+");
        });
        assertThat(properties.feeds()).extracting(MobilithekProperties.Feed::url).doesNotHaveDuplicates();
        assertThat(properties.subscribedFeeds()).extracting(MobilithekProperties.Feed::subscriptionId)
                .allSatisfy(id -> assertThat(id).matches("\\d+"))
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("static feeds are configured where the live feed publishes internal ids, each with its own subscription")
    void staticFeedsAreDistinct() throws IOException {
        MobilithekProperties properties = shipped();

        List<String> staticIds = properties.feeds().stream()
                .filter(MobilithekProperties.Feed::hasStaticFeed)
                .map(MobilithekProperties.Feed::staticSubscriptionId).toList();
        List<String> liveIds = properties.feeds().stream().map(MobilithekProperties.Feed::subscriptionId).toList();

        assertThat(staticIds).hasSizeGreaterThanOrEqualTo(11).doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(id).matches("\\d+"))
                .doesNotContainAnyElementsOf(liveIds);
        assertThat(properties.feeds()).filteredOn(MobilithekProperties.Feed::hasStaticFeed)
                .extracting(MobilithekProperties.Feed::publisher)
                .contains("Wirelane GmbH", "Hamburger Energienetze GmbH (eRound)", "vaylens GmbH");
        assertThat(properties.staticRefreshInterval()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    @DisplayName("the CC BY offerings are credited under CC BY, because that licence is the reason credit is per feed")
    void ccByFeedsSaySo() throws IOException {
        assertThat(shipped().feeds())
                .filteredOn(feed -> feed.licence().contains("CC BY 4.0"))
                .extracting(MobilithekProperties.Feed::publisher)
                .containsExactlyInAnyOrder("EnBW AG", "Eco-Movement", "GLS Mobility GmbH");
    }

    @Test
    @DisplayName("without a certificate the shipped configuration is off, and the timings are the documented ones")
    void shippedDefaults() throws IOException {
        MobilithekProperties properties = shipped();

        assertThat(properties.hasCertificate()).isFalse();
        assertThat(properties.pollInterval()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.maxAge()).isEqualTo(Duration.ofHours(72));
        assertThat(properties.brokerUrl()).isEqualTo("https://mobilithek.info:8443/mobilithek/api/v1.0/subscription/datexv3");
    }
}
