package de.joinside.evmap_service.sync.mobilithek;

import de.joinside.evmap_service.mobilithek.MobilithekConnection;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * The static AFIR feeds of the Mobilithek as Germany's master data (ADR 0025). Named apart from the live provider's
 * {@code availability.mobilithek.MobilithekProperties}: the two deployables bind the same certificate secrets from
 * their own configuration.
 *
 * @param enabled          turns the source off without removing it from the deployment
 * @param brokerUrl        the broker's DATEX II v3 client-pull endpoint, as for the live provider
 * @param keystorePath     the organisation's PKCS#12 machine certificate as a file
 * @param keystoreBase64   the same, Base64-encoded, from {@code MOBILITHEK_KEYSTORE}; wins over the path. Both blank
 *                         switches the source off with a warning, as Open Charge Map without a key.
 * @param keystorePassword the password sent by SMS. A secret under ADR 0002: never logged, never committed.
 * @param timeout          per request; a static package is up to 121 MB unpacked
 * @param registerSource   the token of the source whose station ids the feeds name ({@code stationIdBNetzA}). Config,
 *                         because no source package names another.
 * @param countryCode      the country the Mobilithek is the access point of; sites elsewhere (23 in Austria, one in
 *                         Italy on 2026-10-03) are skipped, and a site without a country is taken to be here
 * @param maxPackages      ceiling on packages read per feed and run. The broker holds only the latest snapshot of a
 *                         static feed, so more than one is the exception; the newest read wins.
 * @param feeds            the static offerings, operators' own feeds before platforms that relay them: a charge point
 *                         two feeds carry is taken from the first
 */
@ConfigurationProperties("evmap.sync.mobilithek")
record MobilithekSyncProperties(@DefaultValue("true") boolean enabled,
                                @DefaultValue("https://mobilithek.info:8443/mobilithek/api/v1.0/subscription/datexv3") String brokerUrl,
                                @DefaultValue("") String keystorePath,
                                @DefaultValue("") String keystoreBase64,
                                @DefaultValue("") String keystorePassword,
                                @DefaultValue("5m") Duration timeout,
                                @DefaultValue("BNetzA") String registerSource,
                                @DefaultValue("DE") String countryCode,
                                @DefaultValue("5") int maxPackages,
                                @DefaultValue List<Feed> feeds) {

    MobilithekSyncProperties {
        feeds = feeds == null ? List.of() : List.copyOf(feeds);
    }

    /**
     * One static offering.
     *
     * @param key            short, stable name of the feed; the first part of every station id it produces, so it
     *                       must never change once a feed has been ingested
     * @param subscriptionId the "Abonnement-ID" of the organisation's subscription; blank = not subscribed, skipped
     * @param publisher      the operator as the offering names it; the operator name of last resort
     */
    record Feed(String key, String subscriptionId, String publisher) {
        boolean subscribed() {
            return subscriptionId != null && !subscriptionId.isBlank();
        }
    }

    MobilithekConnection connection() {
        return new MobilithekConnection(brokerUrl, keystorePath, keystoreBase64, keystorePassword, timeout);
    }

    List<Feed> subscribedFeeds() {
        return feeds.stream().filter(Feed::subscribed).toList();
    }
}
