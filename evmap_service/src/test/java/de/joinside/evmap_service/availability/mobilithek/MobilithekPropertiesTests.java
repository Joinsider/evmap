package de.joinside.evmap_service.availability.mobilithek;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MobilithekPropertiesTests {

    private static MobilithekProperties properties(String path, String base64, List<MobilithekProperties.Feed> feeds) {
        return new MobilithekProperties(true, "https://broker.invalid", path, base64, "", List.of("DE"),
                Duration.ofSeconds(60), 50, Duration.ofHours(72), Duration.ofMinutes(10), Duration.ofHours(1),
                Duration.ofSeconds(30), feeds);
    }

    @Test
    @DisplayName("a certificate counts as configured from a path or from Base64, and blank is none")
    void certificate() {
        assertThat(properties("", "", List.of()).hasCertificate()).isFalse();
        assertThat(properties(null, null, List.of()).hasCertificate()).isFalse();
        assertThat(properties("  ", " ", List.of()).hasCertificate()).isFalse();
        assertThat(properties("/run/secrets/m.p12", "", List.of()).hasCertificate()).isTrue();
        assertThat(properties("", "MIIK", List.of()).hasCertificate()).isTrue();
        assertThat(properties("", "MIIK", List.of()).hasBase64Certificate()).isTrue();
        assertThat(properties("/run/secrets/m.p12", "", List.of()).hasBase64Certificate()).isFalse();
    }

    @Test
    @DisplayName("only feeds with a subscription id are read; a missing table is an empty one")
    void subscribedFeeds() {
        MobilithekProperties.Feed subscribed = new MobilithekProperties.Feed("123", "EnBW AG", "CC BY 4.0", "u");
        MobilithekProperties.Feed blank = new MobilithekProperties.Feed(" ", "EWE", "CC0", "u");
        MobilithekProperties.Feed missing = new MobilithekProperties.Feed(null, "Tesla", "CC0", "u");

        assertThat(properties("", "", List.of(subscribed, blank, missing)).subscribedFeeds()).containsExactly(subscribed);
        assertThat(properties("", "", null).feeds()).isEmpty();
        assertThat(properties("", "", null).subscribedFeeds()).isEmpty();
    }
}
