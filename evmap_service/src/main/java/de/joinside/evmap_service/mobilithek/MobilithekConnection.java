package de.joinside.evmap_service.mobilithek;

import java.time.Duration;

/**
 * How to reach the Mobilithek broker: where it is, the organisation's machine certificate, and how long to wait.
 * <p>
 * Both deployables pull from the broker — the API for live status ({@code availability.mobilithek}), the sync job
 * for the static descriptions ({@code sync.mobilithek}) — and each binds this from its own configuration, so neither
 * reads the other's (ADR 0025).
 *
 * @param brokerUrl        the broker's DATEX II v3 client-pull endpoint; the subscription id is appended as
 *                         {@code subscriptionID}. Port 8443 is the machine-to-machine entrance and demands the
 *                         client certificate.
 * @param keystorePath     the PKCS#12 file the Mobilithek issued — convenient locally
 * @param keystoreBase64   the same file, Base64-encoded, for deployments that pass secrets as environment variables;
 *                         wins over {@code keystorePath}
 * @param keystorePassword the password sent by SMS with the certificate. A secret under ADR 0002: read from the
 *                         environment, never logged, never committed.
 * @param timeout          connect and read timeout per request
 */
public record MobilithekConnection(String brokerUrl, String keystorePath, String keystoreBase64,
                                   String keystorePassword, Duration timeout) {

    /** Hides the password from logs and exception messages, which would otherwise print every component. */
    @Override
    public String toString() {
        return "MobilithekConnection[brokerUrl=" + brokerUrl + ", certificate="
                + (hasBase64Certificate() ? "base64" : hasCertificate() ? keystorePath : "none") + "]";
    }

    public boolean hasCertificate() {
        return isSet(keystorePath) || isSet(keystoreBase64);
    }

    public boolean hasBase64Certificate() {
        return isSet(keystoreBase64);
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
