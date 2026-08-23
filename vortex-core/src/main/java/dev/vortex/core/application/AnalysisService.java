package dev.vortex.core.application;

import dev.vortex.core.analysis.Analysis;
import dev.vortex.core.analysis.AnalysisState;
import dev.vortex.core.execution.ExecutionState;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.port.PerformanceAssistant;
import dev.vortex.core.port.Repositories.AnalysisRepository;
import dev.vortex.core.port.Repositories.ExecutionRepository;
import dev.vortex.core.shared.AnalysisId;
import dev.vortex.core.shared.ExecutionId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Adds AI interpretation to a completed run, without ever being able to affect its verdict.
 *
 * <p>Analyses accumulate rather than replace. Re-running this against the same execution with a
 * newer model or a revised prompt produces an additional record; the earlier interpretation remains
 * inspectable, along with which model and prompt version produced it. Measurements are immutable;
 * interpretations are versioned.
 *
 * <p>Failure here is contained. If no model is reachable, if inference times out, or if the model
 * returns something that does not validate, the analysis is recorded as failed and the execution is
 * untouched — still completed, still carrying its deterministic verdict.
 */
public final class AnalysisService {

    private final PerformanceAssistant assistant;
    private final EvidenceReferenceValidator validator;
    private final EpistemicIntegrityValidator epistemicValidator;
    private final AnalysisRepository analyses;
    private final ExecutionRepository executions;

    public AnalysisService(PerformanceAssistant assistant, EvidenceReferenceValidator validator,
            EpistemicIntegrityValidator epistemicValidator, AnalysisRepository analyses,
            ExecutionRepository executions) {
        this.assistant = Objects.requireNonNull(assistant, "assistant");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.epistemicValidator = Objects.requireNonNull(epistemicValidator, "epistemicValidator");
        this.analyses = Objects.requireNonNull(analyses, "analyses");
        this.executions = Objects.requireNonNull(executions, "executions");
    }

    /**
     * Interprets a completed execution.
     *
     * <p>Blocking; callers run it on a virtual thread. Requires the execution to have completed and
     * produced a deterministic summary — there is nothing to interpret otherwise, and inventing an
     * interpretation of a failed run would be worse than offering none.
     */
    public Analysis analyze(ExecutionId executionId) {
        TestExecution execution = executions.findById(executionId).orElseThrow(
                () -> new IllegalArgumentException("No execution with id " + executionId));

        if (execution.state() != ExecutionState.COMPLETED || execution.summaryIfPresent().isEmpty()) {
            return analyses.save(Analysis.failed(AnalysisId.generate(), executionId,
                    "This execution has no completed measurements to interpret. Analysis is only "
                            + "available for runs that finished and produced results."));
        }

        var availability = assistant.availability();
        if (!availability.available()) {
            return analyses.save(Analysis.failed(AnalysisId.generate(), executionId,
                    availability.problem() + " " + availability.remedy()));
        }

        Analysis pending = analyses.save(Analysis.pending(AnalysisId.generate(), executionId));

        try {
            Analysis running = analyses.save(pending.transitionTo(AnalysisState.RUNNING));

            Analysis produced = assistant.analyze(
                    executionId, execution.plan(), execution.summary());

            // An assistant that failed stays failed. Forcing the state to COMPLETED here would
            // record an empty interpretation as a successful one, which is precisely the kind of
            // quiet dishonesty this separation exists to prevent.
            if (produced.state() == AnalysisState.FAILED) {
                return analyses.save(Analysis.failed(running.id(), executionId,
                        produced.failureMessage().isBlank()
                                ? "The assistant did not produce an interpretation."
                                : produced.failureMessage()));
            }

            var validated = validator.validate(produced, execution.summary());
            var epistemic = epistemicValidator.validate(
                    validated.analysis(), execution.plan(), execution.summary());

            Analysis complete = new Analysis(
                    running.id(), executionId, AnalysisState.COMPLETED,
                    epistemic.analysis().conclusion(),
                    epistemic.analysis().findings(),
                    epistemic.analysis().recommendations(),
                    epistemic.analysis().missingTelemetry(),
                    epistemic.analysis().nextTest(),
                    epistemic.analysis().provenance(),
                    "");

            return analyses.save(complete);

        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return analyses.save(Analysis.failed(pending.id(), executionId,
                    "The analysis did not complete: " + message
                            + " The measurements for this run are unaffected."));
        }
    }

    /** Every interpretation of an execution, newest first. */
    public List<Analysis> history(ExecutionId executionId) {
        return analyses.findByExecution(executionId);
    }

    /** The most recent usable interpretation, when one exists. */
    public Optional<Analysis> latest(ExecutionId executionId) {
        return analyses.findLatest(executionId).filter(Analysis::isUsable);
    }

    /** Whether analysis can be offered at all right now. */
    public PerformanceAssistant.Availability availability() {
        return assistant.availability();
    }
}
