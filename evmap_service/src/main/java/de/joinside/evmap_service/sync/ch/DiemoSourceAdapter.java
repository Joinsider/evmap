package de.joinside.evmap_service.sync.ch;

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
import java.time.Clock;
import java.time.Instant;
import java.util.stream.Stream;

/**
 * Ingests the Swiss national charging register: the Federal Office of Energy's <em>ich-tanke-strom</em>
 * feed (DIEMO), published as one OICP JSON document on {@code data.geo.admin.ch}.
 * <p>
 * No key, no discovery step and no paging: one stable URL serves the whole country. That makes the
 * adapter about as small as IRVE's; what is not small is the mapping, because the feed lists EVSEs
 * rather than stations and is loosely typed throughout — see {@link DiemoOicpParser} and ADR 0012.
 * <p>
 * The server stores the document gzipped and answers {@code Content-Encoding: gzip}. The JDK's HTTP
 * client does not undo that label, so what lands on disk is a gzip file, while another client would
 * deliver plain JSON. {@link BulkDownload} recognises gzip by its magic bytes and copes with either.
 */
@Component
@ConditionalOnProperty(name = "evmap.sync.enabled", havingValue = "true")
@EnableConfigurationProperties(DiemoProperties.class)
class DiemoSourceAdapter implements SourceAdapter {
    private static final BulkDownload DOWNLOAD = BulkDownload.named("Swiss DIEMO register", "diemo-register", ".json");

    private final DiemoProperties properties;
    private final RestClient restClient;
    private final Clock clock;

    @Autowired
    DiemoSourceAdapter(DiemoProperties properties, RestClient.Builder restClientBuilder) {
        this(properties, configure(properties, restClientBuilder).build(), Clock.systemUTC());
    }

    /** Takes a ready-made client and a clock so tests can bind the first to a mock server. */
    DiemoSourceAdapter(DiemoProperties properties, RestClient restClient, Clock clock) {
        this.properties = properties;
        this.restClient = restClient;
        this.clock = clock;
    }

    private static RestClient.Builder configure(DiemoProperties properties, RestClient.Builder builder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());
        return builder.requestFactory(requestFactory);
    }

    @Override
    public String source() {
        return DiemoOicpParser.SOURCE;
    }

    @Override
    public boolean enabled() {
        return properties.enabled();
    }

    @Override
    public Stream<SourceStation> fetchStations() {
        Instant fetchedAt = clock.instant();
        return DOWNLOAD.fetchAndParse(restClient, URI.create(properties.url()), StandardCharsets.UTF_8,
                reader -> DiemoOicpParser.parse(reader, fetchedAt));
    }
}
