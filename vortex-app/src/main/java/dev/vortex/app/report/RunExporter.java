package dev.vortex.app.report;

import dev.vortex.app.service.EvidenceContextFactory;
import dev.vortex.core.application.RunEvidenceService;
import dev.vortex.core.evidence.ExportFilename;
import dev.vortex.core.evidence.ExportFormat;
import dev.vortex.core.evidence.RunEvidence;
import dev.vortex.core.execution.ExecutionState;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.port.ArtifactStore;
import dev.vortex.core.port.Repositories.AnalysisRepository;
import dev.vortex.core.port.Repositories.ExecutionRepository;
import dev.vortex.core.shared.ExecutionId;
import java.util.Optional;

/**
 * Turns a stored run into a document, for whichever front end asked.
 *
 * <p>One implementation of "export run X as Y", used by the web layer and the command line alike.
 * The alternative is each of them assembling evidence and picking an exporter for itself, which is
 * how a CLI and a UI end up disagreeing about what a report contains.
 */
public final class RunExporter {

    private final ExecutionRepository executions;
    private final AnalysisRepository analyses;
    private final ArtifactStore artifacts;
    private final RunEvidenceService evidenceService;
    private final ExportRegistry exporters;

    private final EvidenceContextFactory evidenceContext;

    public RunExporter(ExecutionRepository executions, AnalysisRepository analyses,
            ArtifactStore artifacts, RunEvidenceService evidenceService, ExportRegistry exporters,
            EvidenceContextFactory evidenceContext) {
        this.executions = executions;
        this.analyses = analyses;
        this.artifacts = artifacts;
        this.evidenceService = evidenceService;
        this.exporters = exporters;
        this.evidenceContext = evidenceContext;
    }

    /** A rendered document, with the name it should be saved under. */
    public record Exported(String filename, String mediaType, byte[] content) {
    }

    /** Why an export could not be produced. Each answers what to do about it. */
    public enum Refusal {
        NO_SUCH_RUN("No run with that id exists in this workspace."),
        NOT_COMPLETED("That run did not complete, so there are no settled measurements to report."),
        UNSUPPORTED_FORMAT("Vortex cannot export that format. Available: "
                + ExportFormat.available() + ".");

        private final String explanation;

        Refusal(String explanation) {
            this.explanation = explanation;
        }

        public String explanation() {
            return explanation;
        }
    }

    /** Thrown when the run cannot be exported. Carries a reason a user can act on. */
    public static final class RefusedException extends RuntimeException {

        private final transient Refusal refusal;

        RefusedException(Refusal refusal) {
            super(refusal.explanation());
            this.refusal = refusal;
        }

        public Refusal refusal() {
            return refusal;
        }
    }

    public Exported export(ExecutionId executionId, ExportFormat format) {
        if (!exporters.supports(format)) {
            throw new RefusedException(Refusal.UNSUPPORTED_FORMAT);
        }
        TestExecution execution = executions.findById(executionId)
                .orElseThrow(() -> new RefusedException(Refusal.NO_SUCH_RUN));

        // A document about a run that produced nothing is a document about nothing.
        if (execution.state() != ExecutionState.COMPLETED) {
            throw new RefusedException(Refusal.NOT_COMPLETED);
        }

        RunEvidence evidence = evidenceService.assemble(execution,
                analyses.findLatest(executionId).orElse(null), null,
                evidenceContext.forExecution(execution),
                artifacts.directoryFor(executionId), artifacts.list(executionId));

        return new Exported(ExportFilename.of(evidence, format), format.mediaType(),
                exporters.export(format, evidence));
    }

    public Optional<TestExecution> find(ExecutionId executionId) {
        return executions.findById(executionId);
    }
}
