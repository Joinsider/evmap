package de.joinside.evmap_service.sync.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Buffers a large published file to disk, then parses it as a stream that cleans up after itself.
 * <p>
 * Every bulk register works this way — the Bundesnetzagentur CSV is ~53 MB, the French IRVE
 * consolidation ~162 MB — and the buffering is the part that matters. The ingestion writes to the
 * database as it reads, so parsing the HTTP response body directly would hold the connection open for
 * the entire ingestion and turn any upstream hiccup into a half-finished run. Downloading first makes
 * "the download failed" and "the ingestion failed" two separate, separately recoverable events.
 * <p>
 * The temp file is deleted when the returned stream is closed, which the run always does — including
 * when the ingestion throws partway through, so an abandoned run leaves no 162 MB file behind.
 */
public final class BulkDownload {
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
    private static final Logger log = LoggerFactory.getLogger(BulkDownload.class);

    private final String label;
    private final String filePrefix;
    private final String fileSuffix;

    private BulkDownload(String label, String filePrefix, String fileSuffix) {
        this.label = label;
        this.filePrefix = filePrefix;
        this.fileSuffix = fileSuffix;
    }

    /**
     * @param label      names the download in log lines and failure messages ("BNetzA register")
     * @param filePrefix temp file prefix, so a leftover file says which source produced it
     */
    public static BulkDownload named(String label, String filePrefix, String fileSuffix) {
        return new BulkDownload(label, filePrefix, fileSuffix);
    }

    /** Parses a downloaded file into records. Called once, with a reader it does not have to close. */
    @FunctionalInterface
    public interface ContentParser<T> {
        Stream<T> parse(Reader reader) throws IOException;
    }

    /**
     * Downloads {@code uri} and hands out a stream over its parsed contents.
     * <p>
     * Failure at any stage — the request, the write, opening the reader, the parser rejecting the
     * layout — deletes the temp file before propagating, so nothing accumulates across failed runs.
     */
    public <T> Stream<T> fetchAndParse(RestClient client, URI uri, Charset charset, ContentParser<T> parser) {
        Path file = fetch(client, uri);
        Reader reader = null;
        try {
            reader = Files.newBufferedReader(file, charset);
            Reader open = reader;
            return parser.parse(reader).onClose(() -> {
                closeQuietly(open);
                deleteQuietly(file);
            });
        } catch (IOException exception) {
            closeQuietly(reader);
            deleteQuietly(file);
            // The cause carries what actually went wrong — "the schema changed", "the header moved".
            // The run records only the message into master.sync_run, so a wrapper that says nothing but
            // "cannot read" turns an actionable failure into one that needs a stack trace to diagnose.
            throw new UncheckedIOException("Cannot read the downloaded " + label + ": "
                    + exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            closeQuietly(reader);
            deleteQuietly(file);
            throw exception;
        }
    }

    /**
     * A temp file only this process's user can read or write.
     * <p>
     * The download lands in the shared temp directory, which every user on the host can list. The
     * JDK already creates temp files owner-only on POSIX systems; the permissions are stated here
     * anyway so that the guarantee does not rest on an implementation detail (java:S5443).
     * <p>
     * There is deliberately no fallback for filesystems without POSIX permissions: the sync runs in
     * a Linux container and is developed on macOS, and a silent unprotected path for a platform
     * nobody deploys to is exactly what this method exists to rule out. On such a filesystem
     * {@code createTempFile} throws {@link UnsupportedOperationException} and the source fails loudly.
     */
    private Path createPrivateTempFile() throws IOException {
        return Files.createTempFile(filePrefix, fileSuffix, OWNER_ONLY);
    }

    private Path fetch(RestClient client, URI uri) {
        Path file;
        try {
            file = createPrivateTempFile();
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot create a temporary file for the " + label + " download", exception);
        }

        long startedAt = System.nanoTime();
        try {
            log.info("Downloading {} from {}", label, uri);
            client.get().uri(uri).exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful())
                    throw new IllegalStateException(label + " download failed with " + response.getStatusCode());
                try (InputStream body = response.getBody()) {
                    return Files.copy(body, file, StandardCopyOption.REPLACE_EXISTING);
                }
            });
            log.info("Downloaded {}: {} bytes in {} ms", label, Files.size(file),
                    (System.nanoTime() - startedAt) / 1_000_000);
            return file;
        } catch (IOException exception) {
            deleteQuietly(file);
            throw new UncheckedIOException(label + " download from " + uri + " failed: "
                    + exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            deleteQuietly(file);
            throw exception;
        }
    }

    private void closeQuietly(Reader reader) {
        if (reader == null) return;
        try {
            reader.close();
        } catch (IOException exception) {
            log.debug("Could not close the {} reader: {}", label, exception.getMessage());
        }
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            log.warn("Could not delete the temporary {} download {}: {}", label, file, exception.getMessage());
        }
    }
}
