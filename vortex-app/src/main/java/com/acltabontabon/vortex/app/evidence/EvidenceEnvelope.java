package com.acltabontabon.vortex.app.evidence;

import com.acltabontabon.vortex.core.evidence.DeterministicFinding;
import com.acltabontabon.vortex.core.evidence.EvidenceProvenance;
import com.acltabontabon.vortex.core.evidence.ObservedSignal;
import com.acltabontabon.vortex.core.evidence.OperationEvidence;
import com.acltabontabon.vortex.core.evidence.RunEvidence;
import com.acltabontabon.vortex.core.metrics.SamplePoint;
import com.acltabontabon.vortex.core.shared.Percentile;
import com.acltabontabon.vortex.core.threshold.Durations;
import com.acltabontabon.vortex.core.threshold.ThresholdResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The published shape of a run's evidence.
 *
 * <p>Hand-written, and deliberately not a serialisation of the domain model. Vortex already has a
 * mapper that turns {@code MeasuredResults} and friends into JSON, but that shape exists to be read
 * back by the same version of Vortex that wrote it — it carries type discriminators for sealed
 * hierarchies, and it changes whenever a record changes. Publishing it would make every internal
 * rename a breaking change for anybody parsing an export.
 *
 * <p>So the mapping is an explicit function instead. When a domain record changes, this file stops
 * compiling and somebody decides what the contract should do about it. That is the entire point.
 *
 * <p>Durations are milliseconds, rates are per second, and percentages are percentages — never
 * fractions. Everything that has a unit is named for it, because a field called {@code p95} that
 * might be seconds or milliseconds is not a contract.
 */
final class EvidenceEnvelope {

    private EvidenceEnvelope() {
    }

    /** Builds the published document. Insertion order is the contract; every map here is ordered. */
    static Map<String, Object> from(RunEvidence evidence) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", EvidenceProvenance.SCHEMA_VERSION);
        envelope.put("run", run(evidence));
        envelope.put("verdict", verdict(evidence));
        envelope.put("workload", workload(evidence));
        envelope.put("performance", performance(evidence));
        envelope.put("criteria", criteria(evidence));
        envelope.put("operations", operations(evidence));
        envelope.put("timeline", timeline(evidence));
        envelope.put("observability", observability(evidence));
        envelope.put("validity", validity(evidence));
        envelope.put("resources", resources(evidence));
        envelope.put("limits", limits(evidence));
        envelope.put("findings", findings(evidence));
        envelope.put("comparison", comparison(evidence));
        envelope.put("provenance", provenance(evidence));
        return envelope;
    }

    private static Map<String, Object> run(RunEvidence evidence) {
        var identity = evidence.identity();
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("id", identity.executionId().value());
        run.put("project", identity.projectId().value());
        run.put("service", identity.serviceName());
        put(run, "serviceVersion", identity.serviceVersionIfPresent().orElse(null));
        run.put("workload", identity.workloadName());
        run.put("testType", identity.testType().name());
        run.put("environment", identity.environmentName());
        run.put("environmentType", identity.environmentType().name());
        run.put("classification", identity.classification().name());
        run.put("dependencyMode", identity.dependencyMode().name());
        run.put("target", identity.targetUrl());
        run.put("requestedAt", identity.requestedAt());
        put(run, "startedAt", identity.startedAt());
        put(run, "finishedAt", identity.finishedAt());
        put(run, "durationMillis", millis(identity.duration()));
        return run;
    }

    private static Map<String, Object> verdict(RunEvidence evidence) {
        Map<String, Object> verdict = new LinkedHashMap<>();
        verdict.put("value", evidence.verdict().name());
        verdict.put("question", evidence.question());
        verdict.put("answer", evidence.answer());
        verdict.put("qualifications", evidence.qualifications());
        return verdict;
    }

    private static Map<String, Object> workload(RunEvidence evidence) {
        var workload = evidence.workload();
        Map<String, Object> published = new LinkedHashMap<>();
        published.put("model", workload.model().name());
        published.put("configuredLevel", workload.configuredPeak().asDouble());
        published.put("configuredUnit", workload.configuredPeak().unit());
        put(published, "achievedRatePerSecond",
                workload.achievedRateIfPresent().map(rate -> rate.asDouble()).orElse(null));
        // Absent under a closed workload, where throughput is an outcome rather than a target.
        // A null here means "not applicable", never "zero".
        put(published, "deliveredFraction", workload.deliveredFraction());
        put(published, "deliveredCaveat", emptyToNull(workload.deliveredCaveat()));
        put(published, "configuredDurationMillis", millis(workload.configuredDuration()));
        put(published, "actualDurationMillis", millis(workload.actualDuration()));
        published.put("requests", workload.requests());
        published.put("failures", workload.failures());
        put(published, "estimatedRequests", workload.estimatedRequestsIfPresent().orElse(null));
        published.put("operationMix", workload.operationMix());
        published.put("scriptSource", workload.scriptSource().name());
        published.put("source", workload.source().describe());
        // Names in full, values already masked by the sanitiser. That a run sent an
        // Authorization header is part of how it was carried out.
        published.put("requestHeaders", workload.requestHeaders());
        return published;
    }

    private static Map<String, Object> performance(RunEvidence evidence) {
        var performance = evidence.performance();
        Map<String, Object> published = new LinkedHashMap<>();
        published.put("requests", performance.requests());
        published.put("failures", performance.failures());
        published.put("errorRatePercent", performance.errorRate().asPercent());
        published.put("latencyMillis", latency(performance.latency()));

        performance.sloBreakpointIfPresent().ifPresent(breakpoint -> {
            Map<String, Object> published2 = new LinkedHashMap<>();
            published2.put("level", breakpoint.level().asDouble());
            published2.put("unit", breakpoint.level().unit());
            published2.put("violated", breakpoint.violatedThresholdIds());
            published2.put("evidenceStrength", breakpoint.strength().name());
            published2.put("stagesObserved", breakpoint.stagesObserved());
            published.put("sloBreakpoint", published2);
        });

        performance.systemSaturationIfPresent().ifPresent(saturation -> {
            Map<String, Object> published2 = new LinkedHashMap<>();
            published2.put("status", saturation.status().name());
            published2.put("evidenceStrength", saturation.strength().name());
            published2.put("explanation", saturation.explanation());
            published.put("systemSaturation", published2);
        });

        performance.headroomIfPresent().ifPresent(headroom -> {
            Map<String, Object> published2 = new LinkedHashMap<>();
            published2.put("testedCapacityPerSecond", headroom.testedCapacity().asDouble());
            published2.put("observedProductionPeakPerSecond",
                    headroom.observedProductionPeak().asDouble());
            published2.put("multiple", headroom.multiple());
            published.put("headroom", published2);
        });

        // An absent measurement is an absent key, never null — but a refusal is itself a fact, and
        // a consumer that finds neither a figure nor a reason cannot tell a decline from a bug.
        performance.headroomRefusalIfPresent()
                .ifPresent(reason -> published.put("headroomNotStated", reason));

        performance.productionIfPresent().ifPresent(production -> {
            Map<String, Object> baseline = new LinkedHashMap<>();
            baseline.put("peakPerSecond", production.peakRate().asDouble());
            baseline.put("fetched", production.wasFetched());
            if (production.hasSource()) {
                baseline.put("source", production.source());
            }
            production.sampleResolutionIfPresent().ifPresent(resolution ->
                    baseline.put("sampleResolutionSeconds", resolution.toSeconds()));
            production.mixCoverageIfPresent().ifPresent(coverage -> {
                baseline.put("mixCoverage", coverage.coverage());
                baseline.put("observedRequests", coverage.totalObservedRequests());
                baseline.put("matchedRequests", coverage.matchedRequests());
            });
            production.provenanceIfPresent().ifPresent(provenance -> {
                baseline.put("provider", provenance.providerId());
                if (!provenance.query().isEmpty()) {
                    baseline.put("query", provenance.query());
                }
            });
            published.put("productionBaseline", baseline);
        });

        return published;
    }

    private static Map<String, Object> latency(com.acltabontabon.vortex.core.metrics.LatencyPercentiles latency) {
        Map<String, Object> published = new LinkedHashMap<>();
        if (latency.isEmpty()) {
            // No distribution was recorded. The record defaults min/mean/max to zero, and
            // publishing those would put three measurements nobody took into a machine-readable
            // contract.
            return published;
        }
        for (Map.Entry<Percentile, Duration> entry : latency.sorted().entrySet()) {
            published.put(entry.getKey().label(), entry.getValue().toMillis());
        }
        put(published, "min", millis(latency.minimum()));
        put(published, "mean", millis(latency.mean()));
        put(published, "max", millis(latency.maximum()));
        return published;
    }

    private static List<Map<String, Object>> criteria(RunEvidence evidence) {
        List<Map<String, Object>> published = new ArrayList<>();
        for (ThresholdResult result : evidence.acceptance().results()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", result.thresholdId());
            row.put("description", result.threshold().describe());
            row.put("verdict", result.verdict().name());
            put(row, "observed", emptyToNull(result.observed()));
            put(row, "note", emptyToNull(result.note()));
            published.add(row);
        }
        return published;
    }

    private static List<Map<String, Object>> operations(RunEvidence evidence) {
        List<Map<String, Object>> published = new ArrayList<>();
        for (OperationEvidence operation : evidence.operations()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", operation.operationId().value());
            row.put("name", operation.name());
            row.put("request", operation.methodAndPath());
            put(row, "sharePercent", emptyToNull(operation.sharePercent()));
            row.put("payloadProvenance", operation.payloadProvenance() == null
                    ? null : operation.payloadProvenance().name());
            // Sources, never values. Absent rather than empty when a run did not record them, so a
            // consumer can tell "this run sent only what the configuration said" apart from "this
            // evidence predates request-data provenance".
            if (operation.hasRequestData()) {
                List<Map<String, Object>> sources = new ArrayList<>();
                for (var origin : operation.requestData()) {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("target", origin.target().name());
                    value.put("name", origin.name());
                    value.put("source", origin.source());
                    sources.add(value);
                }
                row.put("requestData", sources);
            }
            row.put("verdict", operation.verdict().name());
            // False is meaningful and must be present: it distinguishes an operation that never
            // fired from one that succeeded, which zeroes alone cannot.
            row.put("hasTraffic", operation.hasTraffic());
            operation.metricsIfPresent().ifPresent(metrics -> {
                row.put("requests", metrics.requests());
                row.put("failures", metrics.failures());
                row.put("errorRatePercent", metrics.errorRate().asPercent());
                metrics.achievedRateIfPresent().ifPresent(
                        rate -> row.put("achievedRatePerSecond", rate.asDouble()));
                row.put("latencyMillis", latency(metrics.latency()));
            });
            published.add(row);
        }
        return published;
    }

    private static Map<String, Object> timeline(RunEvidence evidence) {
        var timeline = evidence.timeline();
        if (!timeline.isRenderable()) {
            return null;
        }
        Map<String, Object> published = new LinkedHashMap<>();
        published.put("bucketMillis", millis(timeline.series().bucketWidth()));
        List<Map<String, Object>> points = new ArrayList<>();
        for (SamplePoint point : timeline.tableRows()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("at", point.at());
            point.requestRateIfPresent().ifPresent(
                    rate -> row.put("achievedRatePerSecond", rate.asDouble()));
            point.targetLoadIfPresent().ifPresent(level -> {
                row.put("targetLevel", level.asDouble());
                row.put("targetUnit", level.unit());
            });
            point.p95IfPresent().ifPresent(p95 -> row.put("p95Millis", p95.toMillis()));
            row.put("errorRatePercent", point.errorRate().asPercent());
            points.add(row);
        }
        published.put("points", points);
        return published;
    }

    private static List<Map<String, Object>> observability(RunEvidence evidence) {
        List<Map<String, Object>> published = new ArrayList<>();
        for (ObservedSignal signal : evidence.observability().signals()) {
            var observation = signal.observation();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", signal.id());
            row.put("metric", signal.name());
            row.put("source", signal.source().name());
            row.put("unit", observation.unit().name());
            row.put("aggregation", observation.aggregation().name());
            row.put("value", observation.value());
            row.put("windowStart", observation.window().start());
            row.put("windowEnd", observation.window().end());
            if (!observation.dimensions().isEmpty()) {
                row.put("dimensions", observation.dimensions());
            }
            signal.trace().ifPresent(trace -> {
                Map<String, Object> published2 = new LinkedHashMap<>();
                published2.put("start", trace.startValue());
                published2.put("peak", trace.peakValue());
                published2.put("end", trace.endValue());
                put(published2, "peakAt", trace.peakAt());
                row.put("trace", published2);
            });
            signal.provenance().ifPresent(provenance -> {
                Map<String, Object> published2 = new LinkedHashMap<>();
                published2.put("provider", provenance.providerId());
                put(published2, "query", emptyToNull(provenance.query()));
                put(published2, "entity", provenance.entityIdIfPresent().orElse(null));
                put(published2, "url", provenance.sourceUrlIfPresent().orElse(null));
                row.put("provenance", published2);
            });
            published.add(row);
        }
        return published;
    }

    private static List<Map<String, Object>> findings(RunEvidence evidence) {
        List<Map<String, Object>> published = new ArrayList<>();
        for (DeterministicFinding finding : evidence.findings()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", finding.id());
            row.put("level", finding.level().name());
            row.put("headline", finding.headline());
            put(row, "detail", emptyToNull(finding.detail()));
            row.put("evidenceStrength", finding.strength().name());
            row.put("evidence", finding.evidenceIds());
            published.add(row);
        }
        return published;
    }

    private static Map<String, Object> comparison(RunEvidence evidence) {
        return evidence.comparisonIfPresent().map(comparison -> {
            Map<String, Object> published = new LinkedHashMap<>();
            published.put("baselineRunId", comparison.baselineId().value());
            put(published, "baselineLabel", emptyToNull(comparison.baselineLabel()));
            put(published, "baselineFinishedAt", comparison.baselineFinishedAt());
            published.put("comparable", comparison.supportsVerdict());
            put(published, "verdict",
                    comparison.verdictIfPresent().map(Enum::name).orElse(null));
            published.put("differences", comparison.differences());
            List<Map<String, Object>> deltas = new ArrayList<>();
            comparison.deltas().forEach(delta -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("metric", delta.metric());
                row.put("baseline", delta.baseline());
                row.put("candidate", delta.candidate());
                row.put("lowerIsBetter", delta.lowerIsBetter());
                put(row, "percentChange", delta.percentChange().orElse(null));
                deltas.add(row);
            });
            published.put("deltas", deltas);
            return published;
        }).orElse(null);
    }

    /**
     * Whether the experiment was carried out as specified.
     *
     * <p>Top level rather than inside provenance. A reader deciding whether to quote a capacity
     * figure should not have to open a provenance block to learn that the run never generated the
     * load it asked for.
     */
    private static Map<String, Object> validity(RunEvidence evidence) {
        var quality = evidence.quality();
        Map<String, Object> published = new LinkedHashMap<>();
        published.put("grade", grade(quality.quality()));
        published.put("assessed", quality.quality().isAssessed());
        published.put("permitsCapacityClaims", quality.permitsAnyCapacityClaim());
        published.put("findings", quality.findings().stream().map(finding -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", reason(finding.reason()));
            row.put("effect", effect(finding.effect()));
            row.put("statement", finding.statement());
            row.put("evidenceIds", finding.evidenceIds());
            finding.fromLevelIfPresent().ifPresent(level ->
                    row.put("fromLevel", level.displayWithUnit()));
            return row;
        }).toList());
        return published;
    }

    /**
     * Typed resource signals, with the system each describes and the limit it was measured against.
     *
     * <p>Only classified signals appear here; everything else is already in {@code observability}.
     * The separation is the published form of the rule that nothing is promoted by looking like a
     * resource.
     */
    private static List<Map<String, Object>> resources(RunEvidence evidence) {
        return evidence.observability().signals().stream()
                .filter(signal -> signal.resourceIfPresent().isPresent())
                .map(signal -> {
                    var resource = signal.resource();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", resource.signalId());
                    row.put("name", resource.name());
                    row.put("resourceKind", kind(resource.kind()));
                    row.put("scope", scope(resource.scope()));
                    row.put("value", resource.value());
                    row.put("unit", resource.observation().unit().symbol());
                    resource.limitIfPresent().ifPresent(limit -> {
                        Map<String, Object> published = new LinkedHashMap<>();
                        published.put("value", limit.value());
                        published.put("unit", limit.unit().symbol());
                        published.put("basis", basis(limit.basis()));
                        published.put("describedAs", limit.describedAs());
                        row.put("limit", published);
                    });
                    row.put("atItsLimit", resource.isAtItsLimit());
                    return row;
                })
                .toList();
    }

    /** The four limits, each with its own evidence, and which was reached first. */
    private static Map<String, Object> limits(RunEvidence evidence) {
        var performance = evidence.performance();
        Map<String, Object> published = new LinkedHashMap<>();
        performance.sloBreakpointIfPresent().ifPresent(breakpoint ->
                published.put("objectiveBreakpoint", breakpoint.level().displayWithUnit()));
        put(published, "systemSaturation", performance.systemSaturationIfPresent()
                .filter(saturation -> saturation.wasObserved())
                .map(saturation -> saturation.describe())
                .orElse(null));
        return published;
    }

    private static Map<String, Object> provenance(RunEvidence evidence) {
        var provenance = evidence.provenance();
        var versions = provenance.toolVersions();
        Map<String, Object> published = new LinkedHashMap<>();
        published.put("vortexVersion", versions.vortexVersion());
        published.put("engineVersion", versions.engineVersion());
        published.put("runtimeVersion", versions.runtimeVersion());
        put(published, "containerImage", versions.dockerImageIfPresent().orElse(null));
        published.put("configurationHash", provenance.configurationHash());
        put(published, "startedAt", provenance.startedAt());
        put(published, "finishedAt", provenance.finishedAt());
        provenance.observabilityWindowIfPresent().ifPresent(window -> {
            published.put("observabilityWindowStart", window.start());
            published.put("observabilityWindowEnd", window.end());
        });
        published.put("evidenceQueries", provenance.evidenceQueries());
        published.put("secretReferences", provenance.secretReferences());
        published.put("artifactDirectory", provenance.artifactDirectory());
        published.put("artifacts", provenance.artifactNames());
        published.put("reproductionCommand", provenance.reproductionCommand());
        published.put("generatedAt", provenance.generatedAt());

        // The shape of the machine that produced the numbers. A capacity figure copied to another
        // machine six months later is not reproducible without it.
        var host = provenance.host();
        if (host.isKnown()) {
            Map<String, Object> shape = new LinkedHashMap<>();
            shape.put("operatingSystem", host.operatingSystem());
            put(shape, "osVersion", emptyToNull(host.osVersion()));
            shape.put("architecture", host.architecture());
            shape.put("availableProcessors", host.availableProcessors());
            shape.put("totalMemoryBytes", host.totalMemoryBytes());
            published.put("host", shape);
        }

        // Every provider consulted, including the ones that answered nothing. Omitting a failure
        // would leave a reader believing nobody had looked there.
        published.put("telemetryProviders", provenance.telemetry().providers().stream()
                .map(provider -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", provider.providerId());
                    row.put("availability", availability(provider.availability()));
                    row.put("metricsRequested", provider.metricsRequested());
                    row.put("metricsReturned", provider.metricsReturned());
                    return row;
                })
                .toList());
        return published;
    }

    // ------------------------------------------------------------- the published vocabulary

    /*
     * Every enum below is published through an exhaustive switch with no default branch.
     *
     * That is the point. This envelope is hand-written, so a new enum constant would otherwise
     * flow through silently and appear in an export under whatever name the enum happens to have —
     * or not at all. Without a default, adding a constant stops this file compiling until somebody
     * decides what the published contract calls it, which is a decision that belongs to whoever
     * changes the contract rather than to whoever reads it afterwards.
     *
     * The wire strings are deliberately independent of the Java names: renaming a constant must not
     * silently change what a consumer pinned against.
     */

    private static String grade(com.acltabontabon.vortex.core.validity.RunQuality quality) {
        return switch (quality) {
            case VALID -> "valid";
            case DEGRADED -> "degraded";
            case INVALID -> "invalid";
            case NOT_ASSESSED -> "not_assessed";
        };
    }

    private static String reason(com.acltabontabon.vortex.core.validity.ValidityReason reason) {
        return switch (reason) {
            case OFFERED_LOAD_NOT_GENERATED -> "offered_load_not_generated";
            case GENERATOR_SATURATED -> "generator_saturated";
            case GENERATOR_HOST_UNDER_PRESSURE -> "generator_host_under_pressure";
            case RUN_TOO_SHORT -> "run_too_short";
            case INSUFFICIENT_SAMPLES -> "insufficient_samples";
            case WARM_UP_NOT_COMPLETED -> "warm_up_not_completed";
            case TELEMETRY_INCOMPLETE -> "telemetry_incomplete";
            case TARGET_UNAVAILABLE_DURING_RUN -> "target_unavailable_during_run";
            case EXECUTION_INTERRUPTED -> "execution_interrupted";
            case WINDOW_MISALIGNED -> "window_misaligned";
        };
    }

    private static String effect(com.acltabontabon.vortex.core.validity.ValidityEffect effect) {
        return switch (effect) {
            case QUALIFIES -> "qualifies";
            case WITHHOLDS_CAPACITY -> "withholds_capacity";
            case WITHHOLDS_ALL_CLAIMS -> "withholds_all_claims";
        };
    }

    private static String kind(com.acltabontabon.vortex.core.resource.ResourceKind kind) {
        return switch (kind) {
            case CPU -> "cpu";
            case MEMORY -> "memory";
            case NETWORK -> "network";
            case DISK -> "disk";
            case RUNTIME_MEMORY -> "runtime_memory";
            case RUNTIME_PAUSE -> "runtime_pause";
            case POOL -> "pool";
            case QUEUE -> "queue";
            case THREADS -> "threads";
        };
    }

    private static String scope(com.acltabontabon.vortex.core.resource.ResourceScope scope) {
        return switch (scope) {
            case SYSTEM_UNDER_TEST -> "system_under_test";
            case LOAD_GENERATOR -> "load_generator";
            case LOAD_GENERATOR_HOST -> "load_generator_host";
            case DEPENDENCY -> "dependency";
        };
    }

    private static String basis(com.acltabontabon.vortex.core.resource.LimitBasis basis) {
        return switch (basis) {
            case PUBLISHED_BY_PROVIDER -> "published_by_provider";
            case INHERENT_TO_UNIT -> "inherent_to_unit";
            case VORTEX_CONFIGURED -> "vortex_configured";
        };
    }

    private static String availability(com.acltabontabon.vortex.core.metrics.TelemetryAvailability availability) {
        return switch (availability) {
            case AVAILABLE -> "available";
            case NO_DATA -> "no_data";
            case UNSUPPORTED -> "unsupported";
            case UNREACHABLE -> "unreachable";
            case UNAUTHORIZED -> "unauthorized";
            case MALFORMED -> "malformed";
        };
    }

    /**
     * Adds a key only when there is a value.
     *
     * <p>An absent optional is an absent key, never {@code null}. A consumer checking for a field
     * should not have to distinguish "Vortex did not measure this" from "Vortex measured it as
     * nothing".
     */
    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static Long millis(Duration duration) {
        return duration == null ? null : duration.toMillis();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Instant identity(Instant instant) {
        return instant;
    }
}
