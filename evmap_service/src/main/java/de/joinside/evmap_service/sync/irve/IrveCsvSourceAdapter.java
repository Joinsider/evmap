package de.joinside.evmap_service.sync.irve;

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
import java.util.stream.Stream;

/**
 * Ingests the French national charging register — the <em>fichier consolidé des IRVE</em> published by
 * Etalab on data.gouv.fr under Licence Ouverte 1.0.
 * <p>
 * French operators and municipalities are each obliged to publish their own file; Etalab consolidates
 * all of them daily into the single download this adapter reads. There is no API and no discovery
 * step: one stable resource id redirects to the current date-stamped edition, which is why this
 * adapter is markedly smaller than its German counterpart despite covering twice as many charge
 * points. See ADR 0012.
 */
@Component
@ConditionalOnProperty(name = "evmap.sync.enabled", havingValue = "true")
@EnableConfigurationProperties(IrveProperties.class)
class IrveCsvSourceAdapter implements SourceAdapter {
    private static final BulkDownload DOWNLOAD =
            BulkDownload.named("IRVE consolidation", "irve-consolidation", ".csv");

    private final IrveProperties properties;
    private final RestClient restClient;

    @Autowired
    IrveCsvSourceAdapter(IrveProperties properties, RestClient.Builder restClientBuilder) {
        this(properties, configure(properties, restClientBuilder).build());
    }

    /** Takes a ready-made client so tests can supply one bound to a mock server. */
    IrveCsvSourceAdapter(IrveProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    private static RestClient.Builder configure(IrveProperties properties, RestClient.Builder builder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());
        // The configured URL answers 302 to static.data.gouv.fr; HttpURLConnection follows that itself
        // because both ends are HTTPS. A future move to a plain-HTTP host would silently stop working.
        return builder.requestFactory(requestFactory);
    }

    @Override
    public String source() {
        return IrveCsvParser.SOURCE;
    }

    @Override
    public boolean enabled() {
        return properties.enabled();
    }

    @Override
    public Stream<SourceStation> fetchStations() {
        return DOWNLOAD.fetchAndParse(restClient, URI.create(properties.csvUrl()),
                StandardCharsets.UTF_8, IrveCsvParser::parse);
    }
}
