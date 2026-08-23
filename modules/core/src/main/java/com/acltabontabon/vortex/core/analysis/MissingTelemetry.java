package com.acltabontabon.vortex.core.analysis;

import java.util.Objects;

/**
 * A measurement that would have improved the analysis but was not collected.
 *
 * <p>Stating this explicitly is more useful than a confident conclusion drawn from absent data.
 * "Vortex cannot identify the primary saturation point because CPU, JVM and connection-pool
 * telemetry were not collected" tells an engineer exactly what to do next; a fabricated bottleneck
 * sends them somewhere else entirely.
 *
 * @param what        the missing measurement
 * @param whyItMatters what it would have made possible
 * @param howToCollect how to capture it next time
 */
public record MissingTelemetry(String what, String whyItMatters, String howToCollect) {

    public MissingTelemetry {
        Objects.requireNonNull(what, "what");
        whyItMatters = whyItMatters == null ? "" : whyItMatters;
        howToCollect = howToCollect == null ? "" : howToCollect;
    }
}
