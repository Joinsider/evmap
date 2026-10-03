package de.joinside.evmap_service.mobilithek;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * The one request the Mobilithek knows: "the next package of this subscription after this timestamp".
 * <p>
 * An interface so that the callers' state machines — the live provider's snapshot, delta, 204, 304 and back-off, the
 * sync adapter's daily snapshot — are testable without a machine certificate or the network. {@link HttpsMobilithekBroker} is the only production implementation.
 */
public interface MobilithekBroker {

    /**
     * The cursor of a subscription nobody has read yet. The broker answers it with its oldest buffered package, which
     * is by definition the last full one (Schnittstellenbeschreibung §4.3, §4.8).
     */
    String FROM_THE_START = "Thu, 01 Jan 1970 00:00:00 GMT";

    /**
     * @param subscriptionId  the organisation's subscription
     * @param ifModifiedSince the previous response's {@code Last-Modified}, verbatim. Never {@code null}: the broker
     *                        answers a request without it with only the newest package, which for a delta feed is
     *                        meaningless on its own — the first request of a feed asks with the epoch instead and
     *                        gets the last full package.
     */
    Response next(String subscriptionId, String ifModifiedSince) throws IOException;

    /**
     * @param status       the HTTP status
     * @param lastModified the {@code Last-Modified} header, the cursor for the next request; {@code null} when absent
     * @param body         the decompressed package; empty unless {@code status} is 200. Closed with the response.
     */
    record Response(int status, String lastModified, InputStream body) implements Closeable {
        @Override
        public void close() throws IOException {
            body.close();
        }
    }
}
