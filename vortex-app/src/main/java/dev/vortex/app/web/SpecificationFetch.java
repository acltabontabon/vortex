package dev.vortex.app.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Fetches an API specification document over HTTP, for the two controllers that let a user point
 * Vortex at a URL to import.
 *
 * <p>Reads at most {@code maxBytes} regardless of how much the server sends or claims to send: the
 * body is read as a stream and abandoned the moment it exceeds the cap, rather than buffered in full
 * and only checked afterwards — a size check performed after the whole body is already in memory is
 * not a bound on memory, it is a bound on the error message.
 */
final class SpecificationFetch {

    private SpecificationFetch() {
    }

    /**
     * @throws IllegalArgumentException for anything the caller should report to the user: an
     *                                   unsupported scheme, an unreachable host, an error status, or
     *                                   a document over {@code maxBytes}
     */
    static String fetch(HttpClient client, String url, int maxBytes) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("That is not a valid URL: " + url);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new IllegalArgumentException(
                    "Vortex only fetches API descriptions over http or https.");
        }

        try {
            HttpResponse<InputStream> response = client.send(
                    HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(20))
                            .header("Accept", "application/yaml, application/json, text/yaml, */*")
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() >= 400) {
                response.body().close();
                throw new IllegalArgumentException(
                        url + " returned HTTP " + response.statusCode()
                                + ". Check that the address serves the document itself rather than "
                                + "a documentation page or a login redirect.");
            }

            byte[] bytes;
            try (InputStream in = response.body()) {
                bytes = in.readNBytes(maxBytes + 1);
            }
            if (bytes.length > maxBytes) {
                throw new IllegalArgumentException("That document is larger than Vortex will import.");
            }
            return new String(bytes, charsetOf(response));
        } catch (IOException e) {
            throw new IllegalArgumentException("Vortex could not reach " + url + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The import was interrupted");
        }
    }

    /** Falls back to UTF-8 for anything absent, unparseable or unsupported — never fails on this. */
    private static Charset charsetOf(HttpResponse<?> response) {
        Optional<String> contentType = response.headers().firstValue("content-type");
        if (contentType.isEmpty()) {
            return StandardCharsets.UTF_8;
        }
        for (String part : contentType.get().split(";")) {
            String trimmed = part.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                try {
                    return Charset.forName(trimmed.substring("charset=".length()).trim());
                } catch (RuntimeException e) {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }
}
