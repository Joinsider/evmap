package de.joinside.evmap_service.sync.bnetza;

import de.joinside.evmap_service.sync.SourceAdapter;
import de.joinside.evmap_service.sync.SourceStation;
import de.joinside.evmap_service.sync.support.BulkDownload;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Ingests the Bundesnetzagentur Ladesäulenregister from its published CSV bulk download (CC BY 4.0).
 * <p>
 * The register's own REST web service is not self-service — access has to be requested by mail from
 * {@code ladesaeulenregister@bnetza.de} — and the ArcGIS feature service behind the public
 * Ladesäulenkarte now answers {@code 499 Token Required}. The CSV is therefore the only openly
 * reachable full copy of the register. See ADR 0005.
 */
@Component
@ConditionalOnProperty(name = "evmap.sync.enabled", havingValue = "true")
@EnableConfigurationProperties(BnetzaProperties.class)
class BnetzaCsvSourceAdapter implements SourceAdapter {
    /**
     * The published file name carries its edition date, so the link changes with every release and
     * cannot be hard-coded. Matching the whole href also yields that date as the edition fallback.
     */
    private static final Pattern CSV_LINK = Pattern.compile(
            "https://data\\.bundesnetzagentur\\.de/[^\"'\\s]*?Ladesaeulenregister_BNetzA_(\\d{4}-\\d{2}-\\d{2})\\.csv");

    private static final BulkDownload DOWNLOAD =
            BulkDownload.named("BNetzA register", "bnetza-ladesaeulenregister", ".csv");

    private final BnetzaProperties properties;
    private final RestClient restClient;

    @Autowired
    BnetzaCsvSourceAdapter(BnetzaProperties properties, RestClient.Builder restClientBuilder) {
        this(properties, configure(properties, restClientBuilder).build());
    }

    /** Takes a ready-made client so tests can supply one bound to a mock server. */
    BnetzaCsvSourceAdapter(BnetzaProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    private static RestClient.Builder configure(BnetzaProperties properties, RestClient.Builder builder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());
        return builder.requestFactory(requestFactory);
    }

    @Override
    public String source() {
        return BnetzaCsvParser.SOURCE;
    }

    @Override
    public boolean enabled() {
        return properties.enabled();
    }

    @Override
    public Stream<SourceStation> fetchStations() {
        Download download = resolveCsv();
        return DOWNLOAD.fetchAndParse(restClient, download.uri(), StandardCharsets.UTF_8,
                reader -> BnetzaCsvParser.parse(reader, download.editionDate()));
    }

    /**
     * Finds the current CSV. An explicitly configured URL wins, so an edition can be pinned or a local
     * copy used; otherwise the download page is scraped, taking the newest date if several are linked.
     */
    private Download resolveCsv() {
        if (!properties.csvUrl().isBlank()) {
            String configured = properties.csvUrl().trim();
            Matcher matcher = CSV_LINK.matcher(configured);
            return new Download(URI.create(configured), matcher.find() ? editionOf(matcher.group(1)) : Instant.now());
        }

        String page = restClient.get().uri(properties.indexUrl()).retrieve().body(String.class);
        if (page == null)
            throw new IllegalStateException("BNetzA download page " + properties.indexUrl() + " returned no body");

        return CSV_LINK.matcher(page).results()
                .map(match -> new Download(URI.create(match.group()), editionOf(match.group(1))))
                .max(Comparator.comparing(Download::editionDate))
                .orElseThrow(() -> new IllegalStateException(
                        "No Ladesaeulenregister CSV link found on " + properties.indexUrl()
                                + " — the download page layout changed; set evmap.sync.bnetza.csv-url to recover"));
    }

    private static Instant editionOf(String isoDate) {
        return LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** A resolved download: where the register is, and which edition it is. */
    private record Download(URI uri, Instant editionDate) {
    }
}
