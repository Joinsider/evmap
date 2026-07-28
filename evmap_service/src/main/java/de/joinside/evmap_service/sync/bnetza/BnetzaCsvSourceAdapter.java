package de.joinside.evmap_service.sync.bnetza;

import de.joinside.evmap_service.logging.LogContext;
import de.joinside.evmap_service.sync.SourceAdapter;
import de.joinside.evmap_service.sync.SourceStation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private static final Logger log = LoggerFactory.getLogger(BnetzaCsvSourceAdapter.class);

    /**
     * The published file name carries its edition date, so the link changes with every release and
     * cannot be hard-coded. Matching the whole href also yields that date as the edition fallback.
     */
    private static final Pattern CSV_LINK = Pattern.compile(
            "https://data\\.bundesnetzagentur\\.de/[^\"'\\s]*?Ladesaeulenregister_BNetzA_(\\d{4}-\\d{2}-\\d{2})\\.csv");

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
    public Stream<SourceStation> fetchStations() {
        if (!properties.enabled()) {
            log.info("BNetzA adapter disabled by configuration");
            return Stream.empty();
        }

        try (var scope = LogContext.scope(LogContext.SOURCE, BnetzaCsvParser.SOURCE)) {
            Download download = resolveCsv();
            log.info("Fetching BNetzA register from {}", download.uri());
            Path file = toTempFile(download.uri());
            return parse(file, download.editionDate());
        }
    }

    /**
     * Hands out a lazy stream over the buffered download. The parser closes the reader when the stream
     * is closed; the deletion is chained onto the same close so a completed — or abandoned — run leaves
     * no 53 MB file behind. {@code Stream.flatMap} closes each adapter's stream even when the ingestion
     * throws, which is what makes that safe.
     */
    private Stream<SourceStation> parse(Path file, Instant editionDate) {
        Reader reader = null;
        try {
            reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
            return BnetzaCsvParser.parse(reader, editionDate).onClose(() -> deleteQuietly(file));
        } catch (IOException exception) {
            closeQuietly(reader);
            deleteQuietly(file);
            throw new UncheckedIOException("Cannot read the downloaded BNetzA register", exception);
        } catch (RuntimeException exception) {
            closeQuietly(reader);
            deleteQuietly(file);
            throw exception;
        }
    }

    /**
     * Buffers the register to disk before parsing rather than parsing the response body directly.
     * The download is ~53 MB and the ingestion that consumes it writes to the database as it reads;
     * streaming straight through would hold the HTTP connection open for the whole ingestion and turn
     * any upstream hiccup into a half-finished run.
     */
    private Path toTempFile(URI uri) {
        Path file;
        try {
            file = Files.createTempFile("bnetza-ladesaeulenregister", ".csv");
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot create a temporary file for the BNetzA download", exception);
        }

        long startedAt = System.nanoTime();
        try {
            restClient.get().uri(uri).exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful())
                    throw new IllegalStateException("BNetzA download failed with " + response.getStatusCode());
                try (InputStream body = response.getBody()) {
                    return Files.copy(body, file, StandardCopyOption.REPLACE_EXISTING);
                }
            });
            log.info("Downloaded BNetzA register: {} bytes in {} ms",
                    Files.size(file), (System.nanoTime() - startedAt) / 1_000_000);
            return file;
        } catch (IOException exception) {
            deleteQuietly(file);
            throw new UncheckedIOException("BNetzA download from " + uri + " failed", exception);
        } catch (RuntimeException exception) {
            deleteQuietly(file);
            throw exception;
        }
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

    private static void closeQuietly(Reader reader) {
        if (reader == null) return;
        try {
            reader.close();
        } catch (IOException exception) {
            log.debug("Could not close the register reader: {}", exception.getMessage());
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            log.warn("Could not delete the temporary register download {}: {}", file, exception.getMessage());
        }
    }

    /** A resolved download: where the register is, and which edition it is. */
    private record Download(URI uri, Instant editionDate) {
    }
}
