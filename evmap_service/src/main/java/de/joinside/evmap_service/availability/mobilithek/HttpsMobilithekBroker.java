package de.joinside.evmap_service.availability.mobilithek;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * The Mobilithek broker over HTTPS with the organisation's machine certificate (mutual TLS), as the
 * <em>Technische Schnittstellenbeschreibung</em> v1.3.2, §7.2.3.2.1 describes it.
 * <p>
 * Only the client side of TLS is ours: the broker presents a public certificate (Telekom Security), so the JVM's
 * default trust store verifies it and no Mobilithek CA has to be shipped.
 */
final class HttpsMobilithekBroker implements MobilithekBroker {
    private static final Logger log = LoggerFactory.getLogger(HttpsMobilithekBroker.class);

    /** Below this the certificate's end is logged as a warning on every start, so it is renewed in time. */
    private static final Duration EXPIRY_WARNING = Duration.ofDays(30);

    private final HttpClient client;
    private final String brokerUrl;
    private final Duration timeout;

    private HttpsMobilithekBroker(HttpClient client, String brokerUrl, Duration timeout) {
        this.client = client;
        this.brokerUrl = brokerUrl;
        this.timeout = timeout;
    }

    /**
     * Loads the machine certificate and builds the client. Throws on a wrong password or an unreadable file, which
     * the provider turns into an ERROR at startup rather than a silent absence of German live data.
     */
    static HttpsMobilithekBroker create(MobilithekProperties properties, Clock clock)
            throws IOException, GeneralSecurityException {
        char[] password = properties.keystorePassword().toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = keystoreOf(properties)) {
            keyStore.load(in, password);
        }
        logExpiry(keyStore, clock.instant());

        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, password);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(keyManagers.getKeyManagers(), null, null);

        HttpClient client = HttpClient.newBuilder()
                .sslContext(tls)
                .connectTimeout(properties.timeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        return new HttpsMobilithekBroker(client, properties.brokerUrl(), properties.timeout());
    }

    private static InputStream keystoreOf(MobilithekProperties properties) throws IOException {
        if (properties.hasBase64Certificate())
            // MIME decoding tolerates the line breaks `base64` inserts by default.
            return new ByteArrayInputStream(Base64.getMimeDecoder().decode(properties.keystoreBase64().trim()));
        return Files.newInputStream(Path.of(properties.keystorePath()));
    }

    /** Logs when the machine certificate ends — the key entry; the issuer's certificate may sit beside it. */
    private static void logExpiry(KeyStore keyStore, Instant now) throws GeneralSecurityException {
        for (String alias : Collections.list(keyStore.aliases()))
            if (keyStore.isKeyEntry(alias) && keyStore.getCertificate(alias) instanceof X509Certificate machine)
                logExpiry(machine.getNotAfter().toInstant(), now);
    }

    private static void logExpiry(Instant notAfter, Instant now) {
        if (notAfter.isBefore(now.plus(EXPIRY_WARNING)))
            log.warn("Mobilithek machine certificate expires {} — request a new one in the Mobilithek", notAfter);
        else
            log.info("Mobilithek machine certificate loaded, valid until {}", notAfter);
    }

    @Override
    public Response next(String subscriptionId, String ifModifiedSince) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(brokerUrl + "?subscriptionID=" + URLEncoder.encode(subscriptionId, StandardCharsets.UTF_8)))
                .timeout(timeout)
                // Mandatory: the broker answers 406 without it and compresses every response.
                .header("Accept-Encoding", "gzip")
                .header("If-Modified-Since", ifModifiedSince)
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the Mobilithek", e);
        }
        String lastModified = response.headers().firstValue("Last-Modified").orElse(null);
        return new Response(response.statusCode(), lastModified, decoded(response));
    }

    /** {@code HttpClient} does not unpack compressed bodies itself. */
    private static InputStream decoded(HttpResponse<InputStream> response) throws IOException {
        String encoding = response.headers().firstValue("Content-Encoding").orElse("");
        InputStream body = response.body();
        if (response.statusCode() != 200) return body;
        return encoding.toLowerCase(Locale.ROOT).contains("gzip") ? new GZIPInputStream(body) : body;
    }
}
