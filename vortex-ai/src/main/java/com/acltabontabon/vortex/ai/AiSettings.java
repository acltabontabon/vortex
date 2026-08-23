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

    private final String provider;
    private final String baseUrl;
    private final AtomicReference<String> model;
    private final Duration timeout;
    private final boolean logPrompts;

    public AiSettings(String provider, String baseUrl, String model, Duration timeout,
            boolean logPrompts) {
        this.provider = Objects.requireNonNullElse(provider, "ollama");
        this.baseUrl = Objects.requireNonNullElse(baseUrl, "http://localhost:11434");
        this.model = new AtomicReference<>(Objects.requireNonNullElse(model, "").trim());
        this.timeout = Objects.requireNonNullElse(timeout, Duration.ofMinutes(3));
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
