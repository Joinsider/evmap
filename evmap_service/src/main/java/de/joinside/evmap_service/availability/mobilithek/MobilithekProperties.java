package de.joinside.evmap_service.availability.mobilithek;

import de.joinside.evmap_service.mobilithek.MobilithekConnection;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * @param enabled             turns the provider off without removing it from the deployment
 * @param brokerUrl           the Mobilithek broker's DATEX II v3 client-pull endpoint; the subscription id
 *                            is appended as {@code subscriptionID}. Port 8443 is the machine-to-machine
 *                            entrance and demands the client certificate.
 * @param keystorePath        the organisation's machine certificate as the PKCS#12 file the Mobilithek
 *                            issued — convenient locally.
 * @param keystoreBase64      the same file, Base64-encoded, for deployments that pass secrets as environment
 *                            variables like every other key of this service; wins over {@code keystorePath}.
 *                            Both blank switches the provider off — a container without a certificate starts
 *                            and answers from the other providers, exactly as OCM does without a key.
 * @param keystorePassword    the password sent by SMS with the certificate. A secret under ADR 0002: read
 *                            from the environment, never logged, never committed.
 * @param countryCodes        countries this provider claims. The Mobilithek is Germany's access point.
 * @param pollInterval        pause between two rounds over every feed. AFIR obliges operators to publish
 *                            within one minute, and a round is one request per feed when nothing changed.
 * @param maxPackagesPerPoll  ceiling on packages fetched from one feed in one round. The broker hands out
 *                            one delta per request; a feed further behind is caught up over the next
 *                            rounds rather than starving the others.
 * @param maxAge              oldest status still reported, as for France: a status nobody has touched for
 *                            days is more likely a feed that stopped than a charge point that stayed free.
 * @param staleAfter          how long a feed's state is still served after its last successful request.
 *                            Beyond it the feed reads as unknown until it answers again.
 * @param backoff             how long a feed is left alone after 403 or 404 — the subscription is gone,
 *                            not approved yet, or the operator's access quota is used up.
 * @param staticRefreshInterval how often a feed's static description is checked for new id translations. It
 *                            changes when an operator builds or renames a charge point, not by the minute, and
 *                            eRound's is 121 MB unpacked.
 * @param timeout             connect and read timeout per request.
 * @param coverageReportInterval how often the log reports, per feed, how many live charge points match a stored
 *                            EVSE-ID (ADR 0015, "Coverage report"). It reads Germany's whole charge point inventory.
 * @param feeds               one entry per subscription. See {@link Feed}.
 */
@ConfigurationProperties("evmap.availability.mobilithek")
record MobilithekProperties(@DefaultValue("true") boolean enabled,
                            @DefaultValue("https://mobilithek.info:8443/mobilithek/api/v1.0/subscription/datexv3") String brokerUrl,
                            @DefaultValue("") String keystorePath,
                            @DefaultValue("") String keystoreBase64,
                            @DefaultValue("") String keystorePassword,
                            @DefaultValue("DE") List<String> countryCodes,
                            @DefaultValue("60s") Duration pollInterval,
                            @DefaultValue("50") int maxPackagesPerPoll,
                            @DefaultValue("72h") Duration maxAge,
                            @DefaultValue("10m") Duration staleAfter,
                            @DefaultValue("1h") Duration backoff,
                            @DefaultValue("24h") Duration staticRefreshInterval,
                            @DefaultValue("30s") Duration timeout,
                            @DefaultValue("1h") Duration coverageReportInterval,
                            @DefaultValue List<Feed> feeds) {

    MobilithekProperties {
        feeds = feeds == null ? List.of() : List.copyOf(feeds);
    }

    /**
     * One subscribed data offering — in practice one charge point operator.
     *
     * @param subscriptionId the id the Mobilithek assigned to the organisation's subscription, shown under
     *                       "Meine Abonnements". Blank means "not subscribed yet" and skips the feed.
     * @param publisher      the operator as the offering names it; credited as "… via Mobilithek"
     * @param licence        the offering's licence title. CC BY requires naming the publisher, which is
     *                       why credit is per feed rather than one line for the platform (ADR 0015).
     * @param url            the offering's page on the Mobilithek, where data and licence are described
     * @param staticSubscriptionId the subscription of the operator's static offering, for operators whose live feed
     *                       names refill points by internal id rather than EVSE-ID (Wirelane, eRound, …). Blank for
     *                       the others. Read only to translate those ids (ADR 0015, L5 open point b).
     */
    record Feed(String subscriptionId, String publisher, String licence, String url, String staticSubscriptionId) {

        boolean subscribed() {
            return isSet(subscriptionId);
        }

        boolean hasStaticFeed() {
            return isSet(staticSubscriptionId);
        }
    }

    /** What the shared broker client needs of this configuration. */
    MobilithekConnection connection() {
        return new MobilithekConnection(brokerUrl, keystorePath, keystoreBase64, keystorePassword, timeout);
    }

    boolean hasCertificate() {
        return connection().hasCertificate();
    }

    boolean hasBase64Certificate() {
        return connection().hasBase64Certificate();
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    List<Feed> subscribedFeeds() {
        return feeds.stream().filter(Feed::subscribed).toList();
    }
}
