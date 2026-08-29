package com.acltabontabon.vortex.app.config;

import com.acltabontabon.vortex.app.VortexProperties;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Prometheus defaults, live and mutable — the same {@code Settings → some page} pattern {@code
 * LoadGeneratorBudgetSettings} already uses: seeded from {@link VortexProperties} at startup, held in
 * an {@link AtomicReference} so a change from Settings → Prometheus defaults takes effect without a
 * restart.
 *
 * <p>Unlike {@code DynatraceMcpSettings}, this implements no port and has no runtime consumer —
 * nothing is ever queried through it. It is read exactly once, when a brand-new service's Prometheus
 * observation-source form mounts, to seed that form's initial values.
 */
@Component
public class PrometheusDefaultsSettings {

    private final AtomicReference<VortexProperties.PrometheusDefaults> current;

    @Autowired
    public PrometheusDefaultsSettings(VortexProperties properties) {
        this(properties.prometheusDefaults());
    }

    private PrometheusDefaultsSettings(VortexProperties.PrometheusDefaults initial) {
        this.current = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    /** For tests and other callers that already have a concrete value in hand and no {@link
     *  VortexProperties} to derive it from. */
    public static PrometheusDefaultsSettings seeded(VortexProperties.PrometheusDefaults initial) {
        return new PrometheusDefaultsSettings(initial);
    }

    public VortexProperties.PrometheusDefaults current() {
        return current.get();
    }

    /** Takes effect on the next read — the next brand-new service's Configure-source panel. */
    public void reconfigure(VortexProperties.PrometheusDefaults next) {
        current.set(Objects.requireNonNull(next, "next"));
    }
}
