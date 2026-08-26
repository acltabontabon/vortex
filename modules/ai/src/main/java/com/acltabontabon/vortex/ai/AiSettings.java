package com.acltabontabon.vortex.ai;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * How the local assistant is configured.
 *
 * <p>{@code model} is the one field a user can change while Vortex is running, from Settings →
 * Local AI — so it is held in an {@link AtomicReference} rather than fixed at startup like the
 * rest. Everything that reads it (availability probing, the chat call itself) already re-reads it
 * per request, so a change takes effect on the next call with no restart.
 */
public final class AiSettings {

    /**
     * {@code explainWorkload}'s prompt is a fifth the size of {@code analyze}'s and needs no
     * evidence-citation reasoning — there is no reason a slow model should be allowed to hold it for
     * as long as a full run interpretation.
     */
    private static final Duration DEFAULT_EXPLAIN_TIMEOUT = Duration.ofSeconds(30);

    private final String provider;
    private final String baseUrl;
    private final AtomicReference<String> model;
    private final Duration timeout;
    private final Duration analyzeTimeout;
    private final Duration compareTimeout;
    private final Duration explainTimeout;
    private final boolean logPrompts;

    public AiSettings(String provider, String baseUrl, String model, Duration timeout,
            boolean logPrompts) {
        this(provider, baseUrl, model, timeout, null, null, null, logPrompts);
    }

    /**
     * @param timeout        the HTTP client's own read timeout — the outer ceiling every call is
     *                       bound by regardless of the deadlines below (see {@code AiConfiguration}).
     * @param analyzeTimeout application-level deadline for {@code analyze}; defaults to
     *                       {@code timeout} when absent
     * @param compareTimeout application-level deadline for {@code compareExecutions}; defaults to
     *                       {@code timeout} when absent
     * @param explainTimeout application-level deadline for {@code explainWorkload}; defaults to a
     *                       much shorter fixed value when absent, since that prompt is small and
     *                       carries no evidence-citation reasoning
     */
    public AiSettings(String provider, String baseUrl, String model, Duration timeout,
            Duration analyzeTimeout, Duration compareTimeout, Duration explainTimeout,
            boolean logPrompts) {
        this.provider = Objects.requireNonNullElse(provider, "ollama");
        this.baseUrl = Objects.requireNonNullElse(baseUrl, "http://localhost:11434");
        this.model = new AtomicReference<>(Objects.requireNonNullElse(model, "").trim());
        this.timeout = Objects.requireNonNullElse(timeout, Duration.ofMinutes(3));
        this.analyzeTimeout = Objects.requireNonNullElse(analyzeTimeout, this.timeout);
        this.compareTimeout = Objects.requireNonNullElse(compareTimeout, this.timeout);
        this.explainTimeout = Objects.requireNonNullElse(explainTimeout, DEFAULT_EXPLAIN_TIMEOUT);
        this.logPrompts = logPrompts;
    }

    public String provider() {
        return provider;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String model() {
        return model.get();
    }

    public Duration timeout() {
        return timeout;
    }

    public Duration analyzeTimeout() {
        return analyzeTimeout;
    }

    public Duration compareTimeout() {
        return compareTimeout;
    }

    public Duration explainTimeout() {
        return explainTimeout;
    }

    public boolean logPrompts() {
        return logPrompts;
    }

    public boolean hasModel() {
        return !model().isBlank();
    }

    /** Switches the model in place, effective for the very next request. */
    public void useModel(String newModel) {
        model.set(Objects.requireNonNullElse(newModel, "").trim());
    }
}
