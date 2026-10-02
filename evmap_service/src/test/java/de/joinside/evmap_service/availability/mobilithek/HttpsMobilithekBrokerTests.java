package de.joinside.evmap_service.availability.mobilithek;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The production client against a local server. TLS itself is the JDK's; what is tested here is everything the
 * broker insists on — gzip asked for and unpacked, the cursor passed through verbatim — and that the machine
 * certificate loads from a file and from Base64.
 * <p>
 * {@code test-machine-certificate.p12} is a self-signed throwaway generated for this test, not a Mobilithek
 * certificate; its password is not a secret.
 */
class HttpsMobilithekBrokerTests {
    private static final Path KEYSTORE = Path.of("src/test/resources/mobilithek/test-machine-certificate.p12");
    private static final String KEYSTORE_PASSWORD = "test-only-password";

    private HttpServer server;
    private final Map<String, String> seen = new ConcurrentHashMap<>();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private static MobilithekProperties properties(String brokerUrl, String path, String base64, String password) {
        return new MobilithekProperties(true, brokerUrl, path, base64, password, List.of("DE"),
                Duration.ofSeconds(60), 50, Duration.ofHours(72), Duration.ofMinutes(10), Duration.ofHours(1),
                Duration.ofSeconds(5), List.of());
    }

    private String serve(int status, byte[] gzippedBody) throws IOException {
        return serve(status, gzippedBody, "gzip");
    }

    private String serve(int status, byte[] gzippedBody, String contentEncoding) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/datexv3", exchange -> {
            seen.put("query", exchange.getRequestURI().getRawQuery());
            seen.put("accept-encoding", String.valueOf(exchange.getRequestHeaders().getFirst("Accept-Encoding")));
            seen.put("if-modified-since", String.valueOf(exchange.getRequestHeaders().getFirst("If-Modified-Since")));
            exchange.getResponseHeaders().add("Last-Modified", "Fri, 02 Oct 2026 08:00:01 GMT");
            if (gzippedBody == null) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                if (contentEncoding != null) exchange.getResponseHeaders().add("Content-Encoding", contentEncoding);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, gzippedBody.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(gzippedBody);
                }
            }
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/datexv3";
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    @Test
    @DisplayName("asks for gzip, passes the cursor through, and hands back the unpacked package")
    void pullsOnePackage() throws Exception {
        String url = serve(200, gzip(AfirStatusJsonTests.DELTA));
        MobilithekBroker broker = HttpsMobilithekBroker.create(
                properties(url, KEYSTORE.toString(), "", KEYSTORE_PASSWORD), Clock.systemUTC());

        try (MobilithekBroker.Response response = broker.next("12345", MobilithekAvailabilityProvider.FROM_THE_START)) {
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.lastModified()).isEqualTo("Fri, 02 Oct 2026 08:00:01 GMT");
            assertThat(AfirStatusJson.parse(response.body()).chargePoints()).containsOnlyKeys("DEEBWE10011");
        }
        assertThat(seen).containsEntry("query", "subscriptionID=12345")
                .containsEntry("accept-encoding", "gzip")
                .containsEntry("if-modified-since", MobilithekAvailabilityProvider.FROM_THE_START);
    }

    @Test
    @DisplayName("a 304 carries no body to unpack")
    void notModified() throws Exception {
        String url = serve(304, null);
        MobilithekBroker broker = HttpsMobilithekBroker.create(
                properties(url, KEYSTORE.toString(), "", KEYSTORE_PASSWORD), Clock.systemUTC());

        try (MobilithekBroker.Response response = broker.next("12345", "Fri, 02 Oct 2026 08:00:01 GMT")) {
            assertThat(response.status()).isEqualTo(304);
            try (InputStream body = response.body()) {
                assertThat(body.readAllBytes()).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("loads the certificate from Base64 as well, line breaks included")
    void loadsBase64Keystore() throws Exception {
        String base64 = Base64.getMimeEncoder().encodeToString(Files.readAllBytes(KEYSTORE));
        assertThat(base64).contains("\r\n");

        MobilithekBroker broker = HttpsMobilithekBroker.create(
                properties("http://127.0.0.1:9/datexv3", "", base64, KEYSTORE_PASSWORD), Clock.systemUTC());

        assertThat(broker).isNotNull();
    }

    @Test
    @DisplayName("a wrong password is an error at startup, not a silent absence of data")
    void rejectsWrongPassword() {
        assertThatThrownBy(() -> HttpsMobilithekBroker.create(
                properties("http://127.0.0.1:9/datexv3", KEYSTORE.toString(), "", "wrong"), Clock.systemUTC()))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("an uncompressed answer is passed through as it is")
    void plainBody() throws Exception {
        String url = serve(200, AfirStatusJsonTests.DELTA.getBytes(StandardCharsets.UTF_8), null);
        MobilithekBroker broker = HttpsMobilithekBroker.create(
                properties(url, KEYSTORE.toString(), "", KEYSTORE_PASSWORD), Clock.systemUTC());

        try (MobilithekBroker.Response response = broker.next("12345", MobilithekAvailabilityProvider.FROM_THE_START)) {
            assertThat(AfirStatusJson.parse(response.body()).chargePoints()).containsOnlyKeys("DEEBWE10011");
        }
    }

    @Test
    @DisplayName("an interrupted request is an I/O failure and keeps the thread's interrupt flag")
    void interrupted() throws Exception {
        String url = serve(304, null);
        MobilithekBroker broker = HttpsMobilithekBroker.create(
                properties(url, KEYSTORE.toString(), "", KEYSTORE_PASSWORD), Clock.systemUTC());

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> broker.next("12345", MobilithekAvailabilityProvider.FROM_THE_START))
                    .isInstanceOf(IOException.class).hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("a certificate close to its end still loads; the warning is the renewal reminder")
    void loadsExpiringCertificate() throws Exception {
        // The fixture is valid for a century; a clock just before its end makes it "about to expire".
        Clock late = Clock.fixed(java.time.Instant.parse("2126-09-01T00:00:00Z"), java.time.ZoneOffset.UTC);

        assertThat(HttpsMobilithekBroker.create(
                properties("http://127.0.0.1:9/datexv3", KEYSTORE.toString(), "", KEYSTORE_PASSWORD), late)).isNotNull();
    }
}
