package de.joinside.evmap_service.sync.es;

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
 * Ingests the Spanish national charging register that the Ministry for the Ecological Transition
 * (MITERD) keeps under Order TED/445/2023, as the DGT's National Access Point publishes it: one DATEX II
 * v3 XML document for the whole country.
 * <p>
 * No key, no discovery step and no paging, like the Swiss and French registers. What is not small is
 * the mapping: the register lists one site per operator, so sites of different operators on one
 * parking lot have to be bundled — see {@link MiterdDatexParser} and ADR 0012, "Spain (L4)".
 */
@Component
@ConditionalOnProperty(name = "evmap.sync.enabled", havingValue = "true")
@EnableConfigurationProperties(MiterdProperties.class)
class MiterdSourceAdapter implements SourceAdapter {
    private static final BulkDownload DOWNLOAD = BulkDownload.named("Spanish MITERD register", "miterd-register", ".xml");

    private final MiterdProperties properties;
    private final RestClient restClient;
    private final Clock clock;

    @Autowired
    MiterdSourceAdapter(MiterdProperties properties, RestClient.Builder restClientBuilder) {
        this(properties, configure(properties, restClientBuilder).build(), Clock.systemUTC());
    }

    /** Takes a ready-made client and a clock so tests can bind the first to a mock server. */
    MiterdSourceAdapter(MiterdProperties properties, RestClient restClient, Clock clock) {
        this.properties = properties;
        this.restClient = restClient;
        this.clock = clock;
    }

    private static RestClient.Builder configure(MiterdProperties properties, RestClient.Builder builder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());
        return builder.requestFactory(requestFactory);
    }

    @Override
    public String source() {
        return MiterdDatexParser.SOURCE;
    }

    @Override
    public boolean enabled() {
        return properties.enabled();
    }

    @Override
    public Stream<SourceStation> fetchStations() {
        Instant fetchedAt = clock.instant();
        return DOWNLOAD.fetchAndParse(restClient, URI.create(properties.url()), StandardCharsets.UTF_8,
                reader -> MiterdDatexParser.parse(reader, fetchedAt));
    }
}
