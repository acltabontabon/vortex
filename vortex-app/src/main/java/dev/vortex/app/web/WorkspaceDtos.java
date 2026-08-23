package dev.vortex.app.web;

import java.util.List;

/**
 * The wire contract the service workspace is built on.
 *
 * <p>Records only, and no logic. They live in one place rather than nested inside whichever
 * controller happened to need them first, because the same shapes appear on several screens: a test
 * row is the same object on Overview, on Tests and in the run chooser, and a reader who learns it
 * once should not meet three near-identical versions of it.
 *
 * <p>Every field is chosen deliberately rather than inherited from whatever a domain object
 * serialises to — the same stance {@link HomeApiController} takes. Two rules follow from that and
 * hold throughout:
 *
 * <ul>
 *   <li><strong>Levels arrive with their unit.</strong> There is no bare number field anywhere here
 *       for an offered load. {@code 50 requests/sec} and {@code 50 VUs} are the same number and
 *       different facts, and no conversion between them exists.
 *   <li><strong>Absence arrives with its reason.</strong> Where the domain refused to compute
 *       something it also said why, and the refusal travels as {@code null} value plus a populated
 *       reason — never as an empty string the client has to interpret.
 * </ul>
 */
public final class WorkspaceDtos {

    private WorkspaceDtos() {
    }

    // ---------------------------------------------------------------- identity and chrome

    /**
     * Everything the workspace chrome shows, on every tab.
     *
     * @param target      where traffic would go, or null when no environment is configured yet —
     *                    which is one of the two states that actually block a run
     * @param release     the release under test, or null. Null is not an error: it limits what
     *                    comparisons can claim and blocks nothing
     * @param running     the run currently in flight, or null
     */
    public record ServiceHeaderDto(String id, String name, String description,
            TargetDto target, int environmentCount, String release,
            ReadinessDto readiness, int operationCount, int testCount, long runCount,
            RunRefDto running) {
    }

    /**
     * The environment a run would target.
     *
     * @param baseUrl              the target's real pre-run address, or the empty string when the
     *                             target has none in truth ({@code DOCKER_IMAGE}/{@code
     *                             DOCKER_COMPOSE} — there is no address to show before a run resolves
     *                             one; do not manufacture one here)
     * @param classification      {@code ISOLATED} or {@code INTEGRATED} — the single most
     *                            consequential fact about what a result may claim
     * @param classificationCaveat the domain's own sentence about what this classification does not
     *                            establish, carried so no screen has to paraphrase it
     * @param targetKind          {@code EXTERNAL_ENDPOINT}, {@code DOCKER_IMAGE} or {@code
     *                            DOCKER_COMPOSE} — matches the wire vocabulary shared with {@code
     *                            ConfigurationApiController.EnvironmentRequest.targetKind}
     * @param targetSummary       the target's own {@code ExecutionTarget.summary()}, e.g. "Docker:
     *                            payment-service:1.4.2" — carries the useful identity when
     *                            {@code baseUrl} is empty
     */
    public record TargetDto(String environmentName, String baseUrl, String environmentTypeLabel,
            String classification, String classificationLabel, String classificationCaveat,
            String dependencyModeLabel, String targetKind, String targetSummary) {
    }

    /**
     * Readiness, as a compact state rather than a page.
     *
     * <p>{@code canRun} and {@code blockerCount} are the two figures the pill itself needs;
     * {@code items} is what opens behind it. The required/advisory split is preserved on each item
     * rather than flattened, because "a test will still run without objectives, what changes is what
     * the result can conclude" is a different statement from "you cannot run yet".
     */
    public record ReadinessDto(boolean canRun, int satisfiedCount, int totalCount,
            int blockerCount, List<ReadinessItemDto> items, String nextStepText) {
    }

    /**
     * @param nextStep the domain's own instruction, never reworded here
     * @param effectivelyRequired whether it is unavoidable on the way to a run, which is not the
     *                      same as {@code requiredToRun} — an import that a required workload cannot
     *                      be defined without is not optional, however little it gates a run itself
     * @param distinct      false while this only narrows a broader item that is still unsatisfied,
     *                      because then one act of configuration answers both and offering the two
     *                      separately invites doing the same thing twice
     * @param available     whether this can be worked on yet, which is a different question from
     *                      whether it is done and from whether it blocks a run — an item can be
     *                      required and unavailable at the same time
     * @param blockedBy     the keys of the prerequisites still outstanding, empty when available —
     *                      keys rather than labels so a caller can point at the items themselves
     * @param blockedReason the domain's own sentence for why it cannot be done yet, null when
     *                      available
     * @param href          where that instruction is carried out — a presentation decision, so it is
     *                      made here and not in {@code ProjectReadiness}
     */
    public record ReadinessItemDto(String key, String kind, String label,
            boolean satisfied, boolean requiredToRun, boolean effectivelyRequired,
            boolean available, boolean distinct,
            List<String> blockedBy, String blockedReason,
            String nextStep, String href) {
    }

    // ---------------------------------------------------------------- tests

    /**
     * One configured, executable thing — a {@code Workload}, called a test everywhere a user can
     * see it.
     *
     * @param question       what this test answers: the author's own objective where they wrote one,
     *                       the test type's standard question otherwise
     * @param levelDisplay   the offered load with its unit, already phrased ("50 requests/sec",
     *                       "ramping to 200 requests/sec")
     * @param source         where the numbers came from, never dropped — a conclusion inherits the
     *                       confidence of its weakest input
     * @param versusProduction the tested level as a multiple of the observed production peak, or
     *                       null when the two are not comparable quantities or no observation exists
     * @param runnable       whether this test could run right now
     * @param problems       why not, in the domain's own words. Empty when {@code runnable}
     * @param drift          whether production has moved away from what this test assumes, or null
     *                       when the question does not apply to it
     * @param capacity       this test's own tested-capacity evidence, or null where no run of it has
     *                       established one — never a different test's or the service's own reading
     * @param range          this test's own production/tested/failing-edge range. Never null;
     *                       {@code renderable} says whether there is anything to draw
     */
    public record TestRowDto(String name, String description, String question,
            String testType, String testTypeLabel, String testTypeQuestion,
            boolean saturating, String model, String modelLabel, String levelDisplay,
            String levelUnit, String durationDisplay, int stageCount, boolean ramping,
            int operationCount, SourceDto source, String versusProduction,
            boolean runnable, List<String> problems, String environmentName,
            RunSummaryDto latestRun, int runCount, DriftDto drift,
            List<MixRowDto> composition, String compositionDrift,
            CapacityDto capacity, CapacityRangeDto range) {
    }

    /**
     * @param kind       {@code PRODUCTION_OBSERVED}, {@code DERIVED_FROM_OBSERVATION} or
     *                   {@code MANUAL}
     * @param derivation the arithmetic behind a derived figure, so the claim is checkable rather
     *                   than merely asserted; null for the other two kinds
     */
    public record SourceDto(String kind, String label, String describe, String detail,
            boolean productionInformed, String observedWindow, String derivation) {
    }

    /**
     * One operation's part of a test's traffic.
     *
     * @param rateDisplay the requests per second this operation receives, or null under a
     *                    concurrency workload where there is no traffic total to divide
     * @param known       whether the operation is in the imported API description. An unknown
     *                    operation is reported rather than hidden: it is why the test will not run
     */
    public record MixRowDto(String operationId, String label, String method, String path,
            String sharePercent, double shareFraction, String rateDisplay, boolean known) {
    }

    /**
     * Whether the production traffic a test was derived from is still what production does.
     *
     * @param kind {@code UNCHANGED}, {@code DRIFTED} or {@code NOT_ASSESSABLE}. The third is the
     *             common case and is never a warning
     */
    public record DriftDto(String kind, String statement, String derivedFrom, String proposedNow,
            String derivation) {
    }

    /** The six test types, with the teaching text the domain has always carried. */
    public record TestTypeDto(String name, String label, String question, String guidance,
            boolean saturating, int configuredTestCount) {
    }

    /**
     * One test type's own most recent evidence, or the honest absence of any — one entry per
     * {@code TestType}, always, so a service that has never run a kind of test still says so rather
     * than omitting it.
     *
     * @param primaryValueKind {@code RATE}, {@code DURATION} or {@code OUTCOME} — which of
     *                         {@code primaryValue} and {@code outcomeLabel} is the number this test
     *                         type is actually about, decided once here rather than re-guessed by
     *                         every renderer from the test type's identity
     * @param primaryValue     the domain's own already-phrased figure for what this test type
     *                         measures — a tested level, a sustained/detected level, or a run's
     *                         duration. Null when {@code hasEvidence} is false
     * @param secondaryValue   tested capacity over observed production peak (e.g. {@code "1.76×"}),
     *                         exactly where {@code CapacityDto.headroom} already supplied one — never
     *                         computed here. Null wherever the domain did not produce one
     * @param running          whether a run of this test type is in flight right now — independent of
     *                         {@code hasEvidence}, so a first-ever run in progress and prior
     *                         completed evidence are never confused with one another
     */
    public record TestTypeEvidenceDto(String testType, String testTypeLabel, boolean hasEvidence,
            String outcome, String outcomeLabel, String primaryValueKind, String primaryValue,
            String secondaryValue, String workloadName, String environmentName, String release,
            String executionId, String relativeTime, String isoTimestamp, String answer,
            boolean running, String runningWorkloadName) {
    }

    // ---------------------------------------------------------------- runs

    /** A run in flight, named just enough for the chrome to link to it. */
    public record RunRefDto(String id, String testName, String testTypeLabel, String stateLabel) {
    }

    /**
     * One execution, as a row.
     *
     * @param verdict     {@code PASS}, {@code FAIL} or {@code NOT_EVALUATED}. The third is never
     *                    reported as a pass
     * @param answer      the deterministic summary's own sentence, or the run's state where it did
     *                    not get far enough to have one
     * @param matchesCurrentTest whether the test as configured now would produce the same
     *                    experiment. False means the definition moved since; null means the test no
     *                    longer exists or could not be resolved
     * @param differences what moved, phrased by {@code ExperimentIdentity}. Empty unless
     *                    {@code matchesCurrentTest} is false
     */
    public record RunSummaryDto(String id, String verdict, String verdictLabel, String stateLabel,
            boolean terminal, String testName, String testType, String testTypeLabel,
            String levelDisplay, String environmentName, String classification, String release,
            String answer, String p95, String durationDisplay, String relativeTime,
            String isoTimestamp, Boolean matchesCurrentTest, List<String> differences) {
    }

    // ---------------------------------------------------------------- capacity

    /**
     * The capacity picture, as marks the domain has already decided may be drawn.
     *
     * <p>The client projects these and gates nothing of its own: no shaded regions, no boundary
     * where {@code BoundaryStatus} refused one, no production mark where the quantities differ. All
     * of that was settled in {@code CapacityRange} before this record was built.
     *
     * @param openEnded whether the highest mark is the tested capacity, in which case the axis
     *                  continues; drawn past a production mark it would claim traffic keeps climbing
     */
    public record CapacityRangeDto(boolean renderable, String unit, List<MarkerDto> markers,
            boolean openEnded) {
    }

    /** @param position where this mark sits on the axis, 0..1, computed by {@code CapacityRange} */
    public record MarkerDto(String kind, String label, String displayWithUnit, double position) {
    }

    /**
     * Tested capacity and its conditions — never the figure alone.
     *
     * @param quotable          whether this observation may be stated as a capacity at all
     * @param boundaryStatus    {@code ESTABLISHED}, {@code FAR_EDGE_NOT_REACHED}, {@code UNSTABLE}
     *                          or {@code NOT_EVALUATED}
     * @param headroom          tested capacity over the observed production peak, or null
     * @param headroomRefusal   why there is no headroom figure. Exactly one of this and
     *                          {@code headroom} is populated, never both and never neither
     */
    public record CapacityDto(String compliantLevel, String label, String boundary,
            String boundaryLabel, boolean quotable, String boundaryStatus,
            String boundaryStatusLabel, String boundaryStrength, String firstNonCompliant,
            String headroom, String headroomRefusal, String serviceVersion, String environmentName,
            String classification, String dependencyMode, String workloadName,
            List<String> operationMix, List<String> objectives, String durationDisplay,
            String measuredAt, String runId,
            // The domain's own conditions() sentences, used verbatim rather than re-derived from
            // the fields above — CapacityObservation already phrases "Environment: staging
            // (Isolated)" and re-composing it here would risk saying it differently in one place.
            List<String> conditions, List<ConstraintCandidateDto> constraintCandidates) {
    }

    /**
     * A resource that was near its limit where the service stopped meeting its objectives —
     * correlated with the degradation, never asserted as its cause. {@code describe()} is the
     * domain's own careful sentence; {@code support} is the confidence-and-alignment line every
     * renderer showed beside it.
     */
    public record ConstraintCandidateDto(String describe, String strengthLabel, String support) {
    }

    // ---------------------------------------------------------------- production

    /**
     * What production actually sends this service.
     *
     * @param attributed whether the figures name where they came from
     * @param fetched    whether Vortex retrieved them itself, as opposed to somebody typing them in.
     *                   The interface must never let a manual figure look like verified telemetry
     */
    public record ProductionDto(String peakRate, String averageRate, String p95ObservedRate,
            String source, boolean attributed, boolean fetched, String observedWindow,
            String note, List<String> qualityFacts, List<MixRowDto> observedMix,
            String mixCoverage) {
    }

    // ---------------------------------------------------------------- pages

    /**
     * The Overview payload.
     *
     * <p>One request, because Overview is a single reading and four round trips would let its parts
     * disagree about which run is the latest.
     */
    public record OverviewDto(ServiceHeaderDto header, ProductionDto production,
            List<String> objectives, CapacityDto capacity, CapacityRangeDto range,
            RunSummaryDto latestRun, List<TestRowDto> tests,
            List<RunSummaryDto> recentRuns, boolean suggestSmokeTest,
            boolean evidencePredatesRelease, String releaseGapText,
            List<TestTypeEvidenceDto> evidenceByTestType) {
    }

    /** The Tests payload. */
    public record TestsDto(ServiceHeaderDto header, List<TestRowDto> tests,
            List<TestTypeDto> testTypes, List<String> environmentNames) {
    }

    /** The Runs payload — every execution recorded for this service, newest first. */
    public record RunsDto(ServiceHeaderDto header, List<RunSummaryDto> runs) {
    }

    /**
     * One release's worth of capacity history.
     *
     * @param current whether this is the release currently under test — the row the reader most
     *                likely came to check
     */
    public record CapacityHistoryEntryDto(String serviceVersion, boolean current,
            List<CapacityDto> observations) {
    }

    /**
     * The Evidence payload: what has been established, in the order a reader asks about it — the
     * conclusion, the conditions it holds under, its history across releases, and the runs behind
     * it.
     *
     * @param releaseMoved whether the release under test has moved since the latest observation was
     *                      measured. Never "stale" — nothing here knows whether the change mattered,
     *                      only that the two versions differ
     */
    public record EvidenceDto(ServiceHeaderDto header, CapacityDto capacity, CapacityRangeDto range,
            String headroomLabel, ProductionDto production, boolean releaseMoved,
            List<CapacityHistoryEntryDto> history, List<RunSummaryDto> runs) {
    }
}
