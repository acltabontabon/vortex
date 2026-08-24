package com.acltabontabon.vortex.app.web;

import com.acltabontabon.vortex.core.project.ProjectConfiguration;
import com.acltabontabon.vortex.core.shared.Concurrency;
import com.acltabontabon.vortex.core.shared.OperationId;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.workload.ConstantArrivalRateShape;
import com.acltabontabon.vortex.core.workload.ConstantConcurrencyShape;
import com.acltabontabon.vortex.core.workload.LoadShape;
import com.acltabontabon.vortex.core.workload.OperationMix;
import com.acltabontabon.vortex.core.workload.RampingArrivalRateShape;
import com.acltabontabon.vortex.core.workload.RampingConcurrencyShape;
import com.acltabontabon.vortex.core.workload.SpikeShapes;
import com.acltabontabon.vortex.core.workload.Stage;
import com.acltabontabon.vortex.core.shared.Weight;
import com.acltabontabon.vortex.core.workload.WeightedOperation;
import com.acltabontabon.vortex.core.workload.Workload;
import com.acltabontabon.vortex.core.workload.WorkloadModel;
import com.acltabontabon.vortex.app.web.TestsApiController.SpikeParamsDto;
import com.acltabontabon.vortex.app.web.TestsApiController.StageInputDto;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Turning what somebody typed into a workload the domain will accept.
 *
 * <p>Extracted so the editor and the API cannot disagree about it. The decoding is small but it is
 * not neutral — a closed workload takes one operation rather than a weight grid, a ramp is
 * apportioned into equal stages, a rename is a delete-then-add — and two implementations of those
 * rules would eventually produce two different workloads from the same intent.
 *
 * <p>Validation itself is not done here. {@code Workload}, {@code OperationMix} and the shapes
 * already reject what they will not accept, with messages written for the person who has to fix it;
 * repeating those checks earlier would mean maintaining a second, quieter opinion about what is
 * valid.
 */
@Component
public class TestDefinitions {

    /**
     * Which operations a test drives, and in what proportion.
     *
     * @param singleOperation the sole operation of a closed workload; ignored under an open one
     * @param weights         operation id to relative weight, for an open workload. Zero and absent
     *                        mean the same thing — not part of this test
     */
    public OperationMix mix(WorkloadModel model, String singleOperation,
            Map<String, Integer> weights) {

        if (model == WorkloadModel.CLOSED) {
            if (singleOperation == null || singleOperation.isBlank()) {
                throw new IllegalArgumentException(
                        "Choose the operation these virtual users will call. A concurrency workload "
                                + "drives one operation: weights would divide the users rather than "
                                + "the traffic, and how much traffic each user produces depends on "
                                + "how fast the service answers.");
            }
            return OperationMix.single(OperationId.of(singleOperation));
        }

        List<WeightedOperation> entries = new ArrayList<>();
        if (weights != null) {
            weights.forEach((operationId, weight) -> {
                if (weight != null && weight > 0) {
                    entries.add(new WeightedOperation(OperationId.of(operationId),
                            Weight.of(weight)));
                }
            });
        }

        if (entries.isEmpty()) {
            throw new IllegalArgumentException(
                    "Give at least one operation a share of the traffic. A workload with no "
                            + "operations describes load that reaches nothing.");
        }
        return OperationMix.of(entries);
    }

    /** The weight grid as it came from a form, where every key is {@code weight-<operationId>}. */
    public Map<String, Integer> weightsFromForm(Map<String, String> form) {
        Map<String, Integer> weights = new LinkedHashMap<>();
        form.forEach((key, value) -> {
            if (!key.startsWith("weight-") || value == null || value.isBlank()) {
                return;
            }
            String operationId = key.substring("weight-".length());
            try {
                weights.put(operationId, Integer.parseInt(value.trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "'" + value + "' is not a whole number, so it cannot be a share of traffic.");
            }
        });
        return weights;
    }

    /**
     * How much load, in what shape, for how long.
     *
     * <p>A ramp is apportioned into equal stages ending at the peak, because stages are the unit of
     * evidence for a breakpoint and evenly spaced ones bracket the limit predictably. Anything more
     * particular than that is expressed in {@code vortex.yaml}, which carries arbitrary stages, or
     * passed as an explicit stage list (see {@link #explicitShape}).
     *
     * <p>{@code peakRate}/{@code stages} drive a ramp for either model — the field is reused for a
     * concurrency workload's peak VU count too, interpreted by {@code model} rather than by name, the
     * same way the composer's own "Ramp to" control is shared between both.
     */
    public LoadShape shape(WorkloadModel model, Double rate, Integer vus, int durationMinutes,
            Double peakRate, int stages) {

        Duration duration = Duration.ofMinutes(Math.max(1, durationMinutes));

        if (model == WorkloadModel.CLOSED) {
            if (peakRate != null && peakRate > 0 && stages > 1) {
                List<Stage> ramp = new ArrayList<>();
                Duration each = duration.dividedBy(stages);
                Concurrency first = null;
                for (int stage = 1; stage <= stages; stage++) {
                    Concurrency target = Concurrency.of((int) Math.round(peakRate * stage / stages));
                    if (first == null) {
                        first = target;
                    }
                    ramp.add(new Stage(target, each));
                }
                return new RampingConcurrencyShape(first, ramp);
            }
            return new ConstantConcurrencyShape(Concurrency.of(vus == null ? 0 : vus), duration);
        }
        if (peakRate != null && peakRate > 0 && stages > 1) {
            List<Stage> ramp = new ArrayList<>();
            Duration each = duration.dividedBy(stages);
            RequestsPerSecond first = null;
            for (int stage = 1; stage <= stages; stage++) {
                RequestsPerSecond target = RequestsPerSecond.of(peakRate * stage / stages);
                if (first == null) {
                    first = target;
                }
                ramp.add(new Stage(target, each));
            }
            return new RampingArrivalRateShape(first, ramp);
        }
        return new ConstantArrivalRateShape(RequestsPerSecond.of(rate == null ? 0 : rate), duration);
    }

    /**
     * A ramp with each stage's level and duration exactly as given — the passthrough path for a
     * recommendation's own stage list (possibly non-uniform, e.g. capped by a safety ceiling), so
     * "Use recommended" reproduces exactly what was recommended rather than the equal-spacing {@link
     * #shape} would reconstruct from a peak and a stage count alone.
     */
    public LoadShape explicitShape(WorkloadModel model, List<StageInputDto> stages) {
        List<Stage> built = stages.stream()
                .map(s -> model == WorkloadModel.CLOSED
                        ? new Stage(Concurrency.of((int) Math.round(s.level())), Duration.ofSeconds(s.durationSeconds()))
                        : new Stage(RequestsPerSecond.of(s.level()), Duration.ofSeconds(s.durationSeconds())))
                .toList();
        return model == WorkloadModel.CLOSED
                ? new RampingConcurrencyShape((Concurrency) built.getFirst().target(), built)
                : new RampingArrivalRateShape((RequestsPerSecond) built.getFirst().target(), built);
    }

    /**
     * The baseline/jump/hold/recovery pattern a spike test needs — the one shape the simple
     * Rate/Stages/Duration controls could never express, so it is always built from its own four
     * parameters via {@link SpikeShapes} rather than a raw stage list, keeping the exact pattern
     * (transition duration, stage order) backend-owned.
     */
    public LoadShape spikeShape(WorkloadModel model, SpikeParamsDto params) {
        Duration before = Duration.ofSeconds(Math.round(params.holdBeforeMinutes() * 60));
        Duration atPeak = Duration.ofSeconds(Math.round(params.holdAtPeakMinutes() * 60));
        return model == WorkloadModel.CLOSED
                ? SpikeShapes.concurrency((int) params.baseline(), (int) params.peak(), before, atPeak)
                : SpikeShapes.arrivalRate(params.baseline(), params.peak(), before, atPeak);
    }

    /** The weights of an existing test, for an editor to open with. */
    public Map<String, Integer> weightsOf(Workload workload) {
        Map<String, Integer> weights = new LinkedHashMap<>();
        workload.operations().entries().forEach(entry ->
                weights.put(entry.operationId().value(), entry.weight().value()));
        return weights;
    }

    /** {@code production-peak} becomes {@code production-peak-copy}, then {@code -copy-2}. */
    public String availableName(ProjectConfiguration configuration, String base) {
        String candidate = base + "-copy";
        int suffix = 2;
        while (configuration.workloadByName(candidate).isPresent()) {
            candidate = base + "-copy-" + suffix++;
        }
        return candidate;
    }

    public String slug(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-_]+", "-");
    }
}
