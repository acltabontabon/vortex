package com.acltabontabon.vortex.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.acltabontabon.vortex.core.port.PerformanceAssistant.Availability;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds out whether a local model can actually be reached, and says what to do when it cannot.
 *
 * <p>The distinctions matter, because the remedy differs completely. Ollama not installed, Ollama
 * installed but not running, and Ollama running with no suitable model pulled are three different
 * situations, and a single "AI unavailable" message helps with none of them.
 *
 * <p>Results are cached briefly. The banner asking about availability is rendered on many pages, and
 * probing a socket on every request would be wasteful without being any more accurate.
 */
public final class OllamaAvailability {

    private static final Logger log = LoggerFactory.getLogger(OllamaAvailability.class);
    private static final Duration CACHE_FOR = Duration.ofSeconds(15);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    private final AiSettings settings;
    private final HttpClient client;
    private final ObjectMapper json = new ObjectMapper();

    private volatile Availability cached;
    private volatile long cachedAt;

    public OllamaAvailability(AiSettings settings) {
        this.settings = settings;
        this.client = HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).build();
    }

    public Availability check() {
        long now = System.nanoTime();
        Availability current = cached;
        if (current != null && Duration.ofNanos(now - cachedAt).compareTo(CACHE_FOR) < 0) {
            return current;
        }
        Availability fresh = probe();
        cached = fresh;
        cachedAt = now;
        return fresh;
    }

    /** Discards the cached result, so a user pressing "Retry" gets a real answer. */
    public void refresh() {
        cached = null;
    }

    /** The models this installation has already pulled, for the settings page. */
    public List<String> installedModels() {
        try {
            return readTags();
        } catch (Exception e) {
            return List.of();
        }
    }

    private Availability probe() {
        List<String> models;
        try {
            models = readTags();
        } catch (java.net.ConnectException e) {
            return Availability.unavailable(settings.provider(),
                    "Ollama was not detected at " + settings.baseUrl() + ".",
                    """
                    Vortex works fully without it — onboarding, workload configuration, execution, \
                    threshold evaluation, history and reports are all unaffected. AI only adds \
                    interpretation on top of results that already exist.

                    To enable it: install Ollama from https://ollama.com, then start it with \
                    'ollama serve'.""");
        } catch (Exception e) {
            log.debug("Ollama probe failed: {}", e.getMessage());
            return Availability.unavailable(settings.provider(),
                    "Vortex could not reach Ollama at " + settings.baseUrl() + ".",
                    "Check that it is running and that the endpoint under Settings → Local AI is "
                            + "correct. Everything else in Vortex continues to work without it.");
        }

        if (models.isEmpty()) {
            return Availability.unavailable(settings.provider(),
                    "Ollama is running but no models have been pulled.",
                    """
                    Pull a small instruct model and select it under Settings → Local AI, for example:

                      ollama pull qwen3:4b

                    Vortex is designed so that a modest local model is enough: it sends a small, \
                    already-calculated evidence package rather than raw output.""");
        }

        if (!settings.hasModel()) {
            return Availability.unavailable(settings.provider(),
                    "Ollama is running but no model has been selected.",
                    "Choose one under Settings → Local AI. Available: " + String.join(", ", models));
        }

        boolean present = models.stream().anyMatch(model ->
                model.equals(settings.model()) || model.startsWith(settings.model() + ":"));
        if (!present) {
            return Availability.unavailable(settings.provider(),
                    "The configured model '" + settings.model() + "' is not installed.",
                    "Pull it with 'ollama pull " + settings.model() + "', or choose one of the "
                            + "models you already have: " + String.join(", ", models));
        }

        return Availability.ready(settings.provider(), settings.model());
    }

    private List<String> readTags() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(settings.baseUrl() + "/api/tags"))
                .GET()
                .timeout(PROBE_TIMEOUT)
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Ollama returned status " + response.statusCode());
        }

        JsonNode models = json.readTree(response.body()).path("models");
        List<String> names = new ArrayList<>();
        for (JsonNode model : models) {
            String name = model.path("name").asText("");
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }
}
