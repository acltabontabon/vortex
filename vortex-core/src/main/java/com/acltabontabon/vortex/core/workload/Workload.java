package com.acltabontabon.vortex.core.workload;

import com.acltabontabon.vortex.core.shared.LoadLevel;
import com.acltabontabon.vortex.core.shared.OperationId;
import com.acltabontabon.vortex.core.shared.WorkloadId;
import com.acltabontabon.vortex.core.threshold.Threshold;
import com.acltabontabon.vortex.core.threshold.ThresholdSet;
import com.acltabontabon.vortex.core.workload.OperationMix;
import com.acltabontabon.vortex.core.workload.Stage;
import com.acltabontabon.vortex.core.workload.LoadShape;
import com.acltabontabon.vortex.core.workload.WorkloadModel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A workload Vortex intends to reproduce against the system under test, together with what must
 * remain true while it does.
 *
 * <p>A workload is a performance <em>experiment</em>: a question, a controlled workload, a traffic
 * composition and a set of objectives. It is reusable and lives in version control; executing it
 * produces a run, and the run produces evidence.
 *
 * <p>What a workload is not:
 * <ul>
 *   <li><strong>Not a business journey.</strong> An operation mix describes aggregate traffic
 *       composition, not one caller doing several things in order.</li>
 *   <li><strong>Not a k6 scenario.</strong> k6's {@code scenarios} block schedules virtual users and
 *       iterations. One Vortex workload normally compiles into several k6 scenarios — one per
 *       operation — because that is the cleanest way to reproduce a mix. Vortex sits one level above
 *       that and does not mirror its object model.</li>
 *   <li><strong>Not a test type.</strong> The {@link TestType} is the pattern this workload follows;
 *       the workload is the concrete workload for this particular service.</li>
 * </ul>
 *
 * @param id          stable identifier
 * @param name        short name used on the command line and as a configuration key
 * @param description what workload this represents, in the user's own words
 * @param objective   what this specific run is trying to establish; falls back to the test type's
 *                    standard question
 * @param type        the established test pattern this follows
 * @param operations  which operations are exercised, and in what proportion
 * @param shape       how much load, in what shape, for how long
 * @param thresholds  objectives declared by this workload, layered over the project's defaults;
 *                    frequently empty
 * @param source      where the workload's numbers came from
 * @param k6Options   raw k6 scenario options merged verbatim into every k6 scenario this compiles
 *                    to, for the cases Vortex has no opinion about; normally empty
 */
public record Workload(
        WorkloadId id,
        String name,
        String description,
        String objective,
        TestType type,
        OperationMix operations,
        LoadShape shape,
        ThresholdSet thresholds,
        WorkloadSource source,
        Map<String, String> k6Options) {

    public static final int MAX_OBJECTIVE_LENGTH = 500;

    public Workload {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(operations, "operations");
        Objects.requireNonNull(shape, "shape");
        name = WorkloadNames.require(name);
        description = description == null ? "" : description.trim();
        objective = objective == null ? "" : objective.trim();
        thresholds = thresholds == null ? ThresholdSet.empty() : thresholds;
        source = source == null ? WorkloadSource.manual() : source;
        k6Options = k6Options == null ? Map.of() : Map.copyOf(k6Options);

        if (objective.length() > MAX_OBJECTIVE_LENGTH) {
            throw new IllegalArgumentException(
                    "workload objective must be at most " + MAX_OBJECTIVE_LENGTH + " characters");
        }

        // A weighted mix means something specific, and it only means it under an open workload.
        if (shape.model() == WorkloadModel.CLOSED && !operations.isSingleOperation()) {
            throw new IllegalArgumentException(
                    "workload '" + name + "' spreads " + operations.size() + " operations across a "
                            + "concurrency workload, which Vortex does not support. Weights divide "
                            + "virtual users rather than traffic, and a virtual user's throughput "
                            + "depends on how fast the operation it calls responds — so 25% of the "
                            + "virtual users does not produce 25% of the requests, and the two drift "
                            + "further apart exactly as the service slows down. Either give this "
                            + "workload a single operation, or switch it to an arrival-rate workload, "
                            + "where the weights genuinely describe traffic.");
        }

        for (Threshold threshold : thresholds.thresholds()) {
            OperationId scoped = threshold.scope().operationIfPresent().orElse(null);
            if (scoped != null && !operations.contains(scoped)) {
                throw new IllegalArgumentException(
                        "workload '" + name + "' sets an objective for '" + scoped.value()
                                + "', which is not one of the operations it exercises ("
                                + operations.operationIds().stream().map(OperationId::value).toList()
                                + "). Add the operation to the mix, or move the objective to a "
                                + "workload that does exercise it.");
            }
        }
    }

    /**
     * The escape hatch: k6 scenario options applied on top of what Vortex generates.
     *
     * <p>Vortex translates engineering intent into k6 configuration and is opinionated about how.
     * Occasionally an experienced performance engineer needs something Vortex has no opinion about
     * — a longer {@code gracefulStop}, a hand-tuned {@code maxVUs} — and being unable to express it
     * would mean abandoning the tool for that run. These are merged last, so an override wins.
     *
     * <p>Deliberately unvalidated beyond being strings. Vortex does not model k6's option schema and
     * pretending to would create a second, always-stale copy of it; k6 rejects what it does not
     * understand, and preflight surfaces that before any traffic is generated.
     */
    public Map<String, String> k6Options() {
        return k6Options;
    }

    public WorkloadModel model() {
        return shape.model();
    }

    public Duration totalDuration() {
        return shape.totalDuration();
    }

    public List<Stage> stages() {
        return shape.stages();
    }

    public LoadLevel peakLevel() {
        return shape.peakLevel();
    }

    public boolean isSingleOperation() {
        return operations.isSingleOperation();
    }

    /**
     * The question this workload answers: the user's own objective when they wrote one, otherwise
     * the standard question for this kind of test.
     *
     * <p>A run without a stated question tends to produce numbers nobody can act on, because there
     * was never an agreed definition of what a good answer would look like. The result page opens by
     * returning to this rather than by showing a chart.
     */
    public String question() {
        return objective.isBlank() ? type.question() : objective;
    }

    public boolean hasCustomObjective() {
        return !objective.isBlank();
    }

    /** The project's objectives with this workload's own layered on top. */
    public ThresholdSet effectiveThresholds(ThresholdSet projectDefaults) {
        ThresholdSet base = projectDefaults == null ? ThresholdSet.empty() : projectDefaults;
        return base.mergedWith(thresholds);
    }
}
