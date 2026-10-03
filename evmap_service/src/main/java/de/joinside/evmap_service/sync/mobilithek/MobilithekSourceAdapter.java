package de.joinside.evmap_service.sync.mobilithek;

import de.joinside.evmap_service.mobilithek.HttpsMobilithekBroker;
import de.joinside.evmap_service.mobilithek.MobilithekBroker;
import de.joinside.evmap_service.sync.SourceAdapter;
import de.joinside.evmap_service.sync.SourceStation;
import de.joinside.evmap_service.vatbasis.VatBasisProperties;
import de.joinside.evmap_service.vatbasis.VatBasisTable;
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
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
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
@EnableConfigurationProperties({MobilithekSyncProperties.class, VatBasisProperties.class})
class MobilithekSourceAdapter implements SourceAdapter {
    private static final Logger log = LoggerFactory.getLogger(MobilithekSourceAdapter.class);

    static final String SOURCE = "MOBILITHEK";
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    private final MobilithekSyncProperties properties;
    private final VatBasisProperties vatBasis;
    /** {@code null} when no certificate is configured or it could not be loaded. */
    private final MobilithekBroker broker;
    private final Clock clock;

    @Autowired
    MobilithekSourceAdapter(MobilithekSyncProperties properties, VatBasisProperties vatBasis) {
        this(properties, vatBasis, brokerFor(properties), Clock.systemUTC());
    }

    /** Test seam: a scripted broker, or {@code null} for "no certificate", and a fixed clock. */
    MobilithekSourceAdapter(MobilithekSyncProperties properties, VatBasisProperties vatBasis, MobilithekBroker broker,
                            Clock clock) {
        this.properties = properties;
        this.vatBasis = vatBasis;
        this.broker = broker;
        this.clock = clock;
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
        // One table per run: an entry a price contradicts is suspended until the next run reads the file again.
        VatBasisTable table = VatBasisTable.of(vatBasis.operators());
        return properties.subscribedFeeds().stream()
                .flatMap(feed -> stationsOf(feed, emittedEvseIds, table).stream());
    }

    private List<SourceStation> stationsOf(MobilithekSyncProperties.Feed feed, Set<String> emittedEvseIds,
                                           VatBasisTable table) {
        Path snapshot = null;
        try {
            snapshot = latestSnapshot(feed);
            if (snapshot == null) return List.of();
            AfirPriceReader prices = new AfirPriceReader(table, feed.publisher() + " via Mobilithek");
            AfirSiteMapper mapper = new AfirSiteMapper(SOURCE, feed, properties.registerSource(),
                    properties.countryCode(), emittedEvseIds, clock.instant(), prices);
            List<SourceStation> stations = new ArrayList<>();
            try (InputStream in = Files.newInputStream(snapshot)) {
                AfirSiteReader.read(in, site -> stations.addAll(mapper.map(site)));
            }
            AfirSiteMapper.Counters counted = mapper.counters;
            log.info("Mobilithek feed {}: {} station(s) with {} charge point(s), {} linked to the register; skipped "
                            + "{} foreign, {} without position, {} charge point(s) relayed by an earlier feed; {} charge "
                            + "point(s) without EVSE-ID", feed.key(), counted.stations, counted.chargePoints,
                    counted.linked, counted.foreign, counted.withoutPosition, counted.relayed, counted.withoutEvseId);
            AfirPriceReader.Counters priced = prices.counters;
            if (priced.priced + priced.uncertain + priced.basisUnknown > 0)
                log.info("Mobilithek feed {}: ad-hoc prices for {} charge point(s); {} without an established VAT "
                        + "basis, {} with a rate not understood (ADR 0022, L6p)", feed.key(), priced.priced,
                        priced.basisUnknown, priced.uncertain);
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
                    // Readable by this process only: the temporary directory is shared with every other user.
                    Path spooled = Files.createTempFile("mobilithek-" + feed.key() + "-", ".datex", OWNER_ONLY);
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
