package dev.vortex.core.port;

import dev.vortex.core.metrics.MetricObservation;
import dev.vortex.core.metrics.TelemetryGap;
import dev.vortex.core.metrics.TimeWindow;
import dev.vortex.core.plan.PlanFingerprint;
import dev.vortex.core.resource.ResourceSignal;
import dev.vortex.core.shared.ExecutionId;
import java.util.List;

/**
 * Collects measurements from the service under test, so Vortex can say something about
 * <em>what</em> saturated rather than only that latency rose.
 *
 * <p>Provider-neutral by design. Vortex is not an observability platform and has no ambition to
 * become one — it consumes telemetry from whatever the team already runs. Actuator, Prometheus and
 * Dynatrace all implement this same interface, and no organisation's choice of tooling is baked into
 * the core.
 *
 * <p>Distinct from {@link ProductionObservationSource}, which asks a monitoring system what
 * <em>production</em> does over weeks. This one watches the deployment under test for the next few
 * minutes. See {@code docs/adr/adr-031-production-observation-has-its-own-port.adoc}.
 *
 * <p>Everything returned carries provenance. "CPU remained below 58%" is only worth something if
 * Vortex knows which provider measured it, over what window and with what aggregation.
 */
public interface ObservabilityProvider {

    /** Short identifier used in metric provenance and settings, e.g. {@code actuator}. */
    String id();

    /**
     * The measurements this provider looks for, in its own naming.
     *
     * <p>On the provider rather than on the collector: the collector is meant to be ignorant of who
     * it is sampling, and a collector holding a constant list of Micrometer metric names is not.
     */
    List<String> defaultMetrics();

    /** Whether this provider can be reached for the given query. */
    boolean isAvailable(ObservabilityQuery query);

    /**
     * What this provider can contribute towards identifying a run in its own interface.
     *
     * <p>Most providers can only be queried over a window, which is a fact about them rather than a
     * shortfall. A provider that can also mark the run in its own timeline says so — and may stop
     * saying so at runtime, if the credentials turn out not to permit it.
     */
    default CorrelationCapability correlationCapability() {
        return CorrelationCapability.QUERY_ONLY;
    }

    /**
     * Collects the requested measurements.
     *
     * <p>Returns only what it could actually measure, together with a stated reason for anything it
     * could not. A provider must never invent or estimate a value it did not observe — and must not
     * let a missing value pass as a silent omission either, because "why is that not here?" is the
     * question the reader will have.
     */
    Collected collect(ObservabilityQuery query);

    /**
     * What a provider had, and what it did not.
     *
     * <p>Both halves together, deliberately. A provider that answered for eleven metrics and failed
     * on the twelfth has produced eleven real measurements and one honest gap, and returning only a
     * list would force the caller to guess which of the two happened.
     */
    record Collected(List<MetricObservation> observations, List<TelemetryGap> gaps,
            CorrelationCapability correlationCapability, List<ResourceSignal> resourceSignals) {

        public Collected {
            observations = observations == null ? List.of() : List.copyOf(observations);
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
            resourceSignals = resourceSignals == null ? List.of() : List.copyOf(resourceSignals);
            correlationCapability =
                    correlationCapability == null ? CorrelationCapability.QUERY_ONLY
                            : correlationCapability;
        }

        /**
         * For a provider that classified some of what it returned.
         *
         * <p>Classified signals belong in <em>both</em> lists: {@code observations} is what gets
         * rendered, cited and exported, and {@code resourceSignals} is what may reach a conclusion
         * about a limit. A provider that puts a signal only in the second would make it invisible
         * to every report.
         */
        public Collected(List<MetricObservation> observations, List<TelemetryGap> gaps,
                List<ResourceSignal> resourceSignals) {
            this(observations, gaps, CorrelationCapability.QUERY_ONLY, resourceSignals);
        }

        /**
         * For a provider that marks its own timeline and classified nothing.
         *
         * <p>This was the canonical shape before signals could be typed, and is kept so a provider
         * whose only distinguishing feature is event markers reads the same as it always did.
         */
        public Collected(List<MetricObservation> observations, List<TelemetryGap> gaps,
                CorrelationCapability correlationCapability) {
            this(observations, gaps, correlationCapability, List.of());
        }

        /**
         * For a provider whose correlation capability never varies per call — most of them; only
         * a provider that attempts a marker on every collection needs the three-argument form.
         */
        public Collected(List<MetricObservation> observations, List<TelemetryGap> gaps) {
            this(observations, gaps, CorrelationCapability.QUERY_ONLY, List.of());
        }

        public static Collected of(List<MetricObservation> observations) {
            return new Collected(observations, List.of());
        }

        public static Collected nothing() {
            return new Collected(List.of(), List.of());
        }

        public boolean isEmpty() {
            return observations.isEmpty();
        }
    }

    /** What a provider can offer towards tying a run to its own view of the system. */
    enum CorrelationCapability {

        /** The run can be found by its window. Every provider can do at least this. */
        QUERY_ONLY("queryable by window"),

        /** The run is marked in the provider's own timeline and can be navigated to directly. */
        EVENT_MARKERS("marked in the provider's timeline");

        private final String label;

        CorrelationCapability(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * What to collect and from where.
     *
     * @param endpoint    where to collect from
     * @param window      the interval of interest
     * @param metricNames the measurements wanted, in the provider's own naming
     * @param correlation which run this is, for providers that can record it; never null
     */
    record ObservabilityQuery(String endpoint, TimeWindow window, List<String> metricNames,
            RunCorrelation correlation) {

        public ObservabilityQuery {
            metricNames = metricNames == null ? List.of() : List.copyOf(metricNames);
            correlation = correlation == null ? RunCorrelation.none() : correlation;
        }

        public ObservabilityQuery(String endpoint, TimeWindow window, List<String> metricNames) {
            this(endpoint, window, metricNames, RunCorrelation.none());
        }
    }

    /**
     * Which run is asking, carried outward so a provider can record it where it is able to.
     *
     * <p>Everything here already existed on the execution; what was missing was carrying it past the
     * port. Note what this is <em>not</em>: it is not experiment identity, which deliberately
     * excludes derived values (ADR-027), and it is not a tag on the generated k6 script, which stays
     * free of Vortex-specific injections (ADR-026). It travels with the query, not with the traffic.
     *
     * @param executionId the run
     * @param fingerprint the plan it executed
     * @param stageLabel  which stage is being asked about, when the question is stage-scoped
     */
    record RunCorrelation(ExecutionId executionId, PlanFingerprint fingerprint, String stageLabel) {

        private static final RunCorrelation NONE = new RunCorrelation(null, null, "");

        public RunCorrelation {
            stageLabel = stageLabel == null ? "" : stageLabel.trim();
        }

        /** For a query made outside any run, such as an availability probe. */
        public static RunCorrelation none() {
            return NONE;
        }

        public static RunCorrelation of(ExecutionId executionId, PlanFingerprint fingerprint) {
            return new RunCorrelation(executionId, fingerprint, "");
        }

        public RunCorrelation forStage(String label) {
            return new RunCorrelation(executionId, fingerprint, label);
        }

        public boolean isKnown() {
            return executionId != null;
        }

        public java.util.Optional<ExecutionId> executionIdIfPresent() {
            return java.util.Optional.ofNullable(executionId);
        }

        public java.util.Optional<PlanFingerprint> fingerprintIfPresent() {
            return java.util.Optional.ofNullable(fingerprint);
        }

        /** A short human-readable name for the run, for a marker's title. */
        public String describe() {
            if (!isKnown()) {
                return "Vortex run";
            }
            return stageLabel.isBlank()
                    ? "Vortex run " + executionId.value()
                    : "Vortex run " + executionId.value() + " · " + stageLabel;
        }
    }
}
