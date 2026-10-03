package de.joinside.evmap_service.sync.mobilithek;

import de.joinside.evmap_service.mobilithek.HttpsMobilithekBroker;
import de.joinside.evmap_service.mobilithek.MobilithekBroker;
import de.joinside.evmap_service.sync.SourceAdapter;
import de.joinside.evmap_service.sync.SourceStation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Germany's master data from the operators' static AFIR feeds on the Mobilithek, the authority for Germany with the
 * BNetzA register as its fallback (ADR 0025).
 * <p>
 * Each run reads every configured feed's latest snapshot — the broker keeps only that for a static feed — and maps
 * its stations, one feed after another in table order. A feed the broker has nothing for (204), or that is not
 * approved (403/404) or not brokered (422), is skipped with a warning: its stations stay as the last run left them. A
 * feed that cannot be read or parsed ends the source's run instead, contained by the run like any broken source
 * (ADR 0013): reading on would let a platform feed emit the charge points the broken operator feed relays, as
 * stations of their own.
 */
@Component
@ConditionalOnProperty(name = "evmap.sync.enabled", havingValue = "true")
@EnableConfigurationProperties(MobilithekSyncProperties.class)
class MobilithekSourceAdapter implements SourceAdapter {
    private static final Logger log = LoggerFactory.getLogger(MobilithekSourceAdapter.class);

    static final String SOURCE = "MOBILITHEK";

    private final MobilithekSyncProperties properties;
    /** {@code null} when no certificate is configured or it could not be loaded. */
    private final MobilithekBroker broker;

    @Autowired
    MobilithekSourceAdapter(MobilithekSyncProperties properties) {
        this(properties, brokerFor(properties));
    }

    /** Test seam: a scripted broker, or {@code null} for "no certificate". */
    MobilithekSourceAdapter(MobilithekSyncProperties properties, MobilithekBroker broker) {
        this.properties = properties;
        this.broker = broker;
    }

    private static MobilithekBroker brokerFor(MobilithekSyncProperties properties) {
        if (!properties.enabled() || !properties.connection().hasCertificate()) return null;
        try {
            return HttpsMobilithekBroker.create(properties.connection(), Clock.systemUTC());
        } catch (IOException | GeneralSecurityException e) {
            // The exception names the failure, never the password.
            log.error("Mobilithek machine certificate could not be loaded — German master data from the Mobilithek is off", e);
            return null;
        }
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public boolean enabled() {
        return properties.enabled();
    }

    @Override
    public Stream<SourceStation> fetchStations() {
        if (broker == null) {
            log.warn("Mobilithek source has no machine certificate (MOBILITHEK_KEYSTORE) — skipping; German stations "
                    + "stay as the register and earlier runs left them");
            return Stream.empty();
        }
        Set<String> emittedEvseIds = new HashSet<>();
        return properties.subscribedFeeds().stream()
                .flatMap(feed -> stationsOf(feed, emittedEvseIds).stream());
    }

    private List<SourceStation> stationsOf(MobilithekSyncProperties.Feed feed, Set<String> emittedEvseIds) {
        Path snapshot = null;
        try {
            snapshot = latestSnapshot(feed);
            if (snapshot == null) return List.of();
            AfirSiteMapper mapper = new AfirSiteMapper(SOURCE, feed, properties.registerSource(),
                    properties.countryCode(), emittedEvseIds);
            List<SourceStation> stations = new ArrayList<>();
            try (InputStream in = Files.newInputStream(snapshot)) {
                AfirSiteReader.read(in, site -> stations.addAll(mapper.map(site)));
            }
            AfirSiteMapper.Counters counted = mapper.counters;
            log.info("Mobilithek feed {}: {} station(s) with {} charge point(s), {} linked to the register; skipped "
                            + "{} foreign, {} without position, {} charge point(s) relayed by an earlier feed; {} charge "
                            + "point(s) without EVSE-ID", feed.key(), counted.stations, counted.chargePoints,
                    counted.linked, counted.foreign, counted.withoutPosition, counted.relayed, counted.withoutEvseId);
            return stations;
        } catch (IOException e) {
            throw new UncheckedIOException("Mobilithek feed " + feed.key() + " could not be read", e);
        } finally {
            deleteQuietly(snapshot);
        }
    }

    /**
     * The feed's newest package, spooled to a temporary file so that a 121 MB snapshot is not held in memory twice;
     * {@code null} when the broker has none for us.
     */
    private Path latestSnapshot(MobilithekSyncProperties.Feed feed) throws IOException {
        Path latest = null;
        String cursor = MobilithekBroker.FROM_THE_START;
        try {
            for (int read = 0; read < properties.maxPackages(); read++) {
                try (MobilithekBroker.Response response = broker.next(feed.subscriptionId(), cursor)) {
                    if (response.status() != 200) {
                        if (latest == null) log.warn("Mobilithek feed {} answered HTTP {} — skipping it this run",
                                feed.key(), response.status());
                        return latest;
                    }
                    Path spooled = Files.createTempFile("mobilithek-" + feed.key() + "-", ".datex");
                    deleteQuietly(latest);
                    latest = spooled;
                    Files.copy(response.body(), spooled, StandardCopyOption.REPLACE_EXISTING);
                    if (response.lastModified() == null) return latest;
                    cursor = response.lastModified();
                }
            }
            return latest;
        } catch (IOException | RuntimeException e) {
            deleteQuietly(latest);
            throw e;
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("Could not delete {}", file, e);
        }
    }
}
