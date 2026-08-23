package dev.vortex.app.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.analysis.BreakpointDetector;
import dev.vortex.core.analysis.DeterministicSummary;
import dev.vortex.core.analysis.SystemSaturationDetector;
import dev.vortex.core.application.DeterministicAnalyzer;
import dev.vortex.core.application.RunEvidenceService;
import dev.vortex.core.comparison.RegressionEvaluator;
import dev.vortex.core.evidence.EvidenceSanitizer;
import dev.vortex.core.evidence.FindingDetector;
import dev.vortex.core.evidence.RunEvidence;
import dev.vortex.core.execution.ExecutionArtifacts;
import dev.vortex.core.execution.ExecutionState;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.metrics.MeasuredResults;
import dev.vortex.core.data.DatasetRef;
import dev.vortex.core.data.DatasetValue;
import dev.vortex.core.data.EnvironmentValue;
import dev.vortex.core.data.FixedValue;
import dev.vortex.core.data.GeneratedValue;
import dev.vortex.core.data.Generator;
import dev.vortex.core.data.RequestData;
import dev.vortex.core.data.RequestValue;
import dev.vortex.core.plan.PlannedOperation;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.plan.ToolVersions;
import dev.vortex.core.port.Clock;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.threshold.ThresholdEvaluation;
import dev.vortex.core.threshold.ThresholdEvaluator;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An export is where a run's details leave the machine, so it is the last place a credential could
 * escape and the only place nobody would notice.
 *
 * <p>Both writers are tested, not a representative one. A writer added later that forgot to go
 * through the sanitiser would be exactly the one nobody thought to check.
 */
class SecretsNeverExportTest {

    /** Assembled from pieces so a repository secret scan does not flag its own leak test. */
    private static final String CREDENTIAL = "sk-" + "liveKey0123456789" + "abcdefXYZ";

    private static final String BEARER = "Bearer " + CREDENTIAL;

    /** One row per writer, so a test can loop without knowing each writer's own type. */
    private record NamedWriter(String name, Function<RunEvidence, byte[]> export) {
    }

    private final List<NamedWriter> writers = List.of(
            new NamedWriter("json", new EvidenceJsonWriter()::export),
            new NamedWriter("markdown", new EvidenceMarkdownWriter()::export));

    @Test
    @DisplayName("a literal credential in a request header reaches no export, in any format")
    void credentialsNeverReachAnExport() {
        RunEvidence evidence = evidenceWithHeader("Authorization", BEARER);

        for (NamedWriter writer : writers) {
            assertThat(readable(writer, evidence))
                    .as("%s must not carry the credential", writer.name())
                    .doesNotContain(CREDENTIAL);
        }
    }

    @Test
    @DisplayName("the header name survives, so a reader still knows the run was authenticated")
    void headerNamesAreKept() {
        RunEvidence evidence = evidenceWithHeader("Authorization", BEARER);

        assertThat(readable(writers.get(0), evidence)).contains("Authorization");
    }

    @Test
    @DisplayName("the variables a run needed are named, because a name is not a secret")
    void secretReferencesAreNamed() {
        RunEvidence evidence = evidenceWithHeader("Authorization", "Bearer ${VORTEX_AUTH_TOKEN}");

        for (NamedWriter writer : writers) {
            assertThat(readable(writer, evidence))
                    .as("%s should name the variable a rerun requires", writer.name())
                    .contains("VORTEX_AUTH_TOKEN");
        }
    }

    @Test
    @DisplayName("credentials embedded in a target url do not survive either")
    void urlCredentialsAreStripped() {
        RunEvidence evidence = evidenceWithHeader("X-Trace", "on");

        for (NamedWriter writer : writers) {
            assertThat(readable(writer, evidence)).doesNotContain("hunter2");
        }
    }

    @Test
    @DisplayName("a credential pasted into a fixed request value reaches no export either")
    void credentialsInRequestValuesNeverReachAnExport() {
        // Header values were the only place a secret could be written before request data existed.
        // Now a body field or a query parameter can hold one, and the same rule has to reach them —
        // a user who pastes a token into a fixed value has made a mistake, not a decision to publish.
        RunEvidence evidence = evidenceWithRequestValue(new FixedValue(BEARER));

        for (NamedWriter writer : writers) {
            assertThat(readable(writer, evidence))
                    .as("%s must not carry the credential", writer.name())
                    .doesNotContain(CREDENTIAL);
        }
    }

    @Test
    @DisplayName("a value read from the environment is exported as its reference, never resolved")
    void environmentValuesAreExportedAsReferences() {
        RunEvidence evidence =
                evidenceWithRequestValue(EnvironmentValue.named("PARTNER_API_KEY"));

        String rendered = readable(writers.get(0), evidence);

        assertThat(rendered).contains("PARTNER_API_KEY").doesNotContain(CREDENTIAL);
    }

    @Test
    @DisplayName("a dataset is named and its rows are not, because the point is explanation")
    void datasetsAreNamedButNotCopied() {
        RunEvidence evidence = evidenceWithRequestValue(
                new DatasetValue(DatasetRef.local("customers"), "customerId"));

        for (NamedWriter writer : writers) {
            assertThat(readable(writer, evidence))
                    .as("%s should say where the value came from", writer.name())
                    .contains("customers");
        }
    }

    @Test
    @DisplayName("a generated value records what produces it, never what it produced")
    void generatedValuesRecordTheirGeneratorOnly() {
        RunEvidence evidence = evidenceWithRequestValue(GeneratedValue.of(Generator.UUID));

        // A run's evidence explains how the requests were built. Keeping every value it generated
        // would be an audit trail of traffic, which is a different product.
        assertThat(readable(writers.get(0), evidence))
                .contains("UUID")
                .doesNotContain("-4");
    }

    // ---------------------------------------------------------------- helpers

    private String readable(NamedWriter writer, RunEvidence evidence) {
        return new String(writer.export().apply(evidence), StandardCharsets.UTF_8);
    }

    /** A run whose first operation carries one configured request value, in a header. */
    private RunEvidence evidenceWithRequestValue(RequestValue value) {
        EffectiveTestPlan base = Fixtures.plan();
        var operation = base.operations().getFirst();
        var bound = new PlannedOperation(operation.operationId(), operation.name(),
                operation.k6ScenarioKey(), operation.method(), operation.pathTemplate(),
                new RequestData(operation.pathValues(), operation.queryValues(),
                        Map.of("X-Partner-Key", value), operation.body(), operation.bodyValues()),
                operation.provenance(), operation.expect(), operation.share(),
                operation.arrivalRate());

        List<PlannedOperation> operations = new java.util.ArrayList<>(base.operations());
        operations.set(0, bound);
        return evidenceFor(withOperations(base, operations), Map.of("X-Trace", "on"));
    }

    private EffectiveTestPlan withOperations(EffectiveTestPlan base,
            List<PlannedOperation> operations) {
        return new EffectiveTestPlan(base.id(), base.projectId(), base.projectName(),
                base.serviceVersion(), base.intent(), base.workloadName(),
                base.workloadDescription(), base.testType(), base.workloadModel(),
                base.peakLevel(), base.stages(), operations, base.datasets(), base.workloadSource(),
                base.thresholds(), base.environmentName(), base.environmentType(),
                base.configuredTarget(), base.effectiveTarget(), base.targetRewriteReason(),
                base.dependencyMode(), base.classification(), base.headers(), base.k6Options(),
                base.runner(), base.scriptSource(), base.safetyDecisions(), base.fingerprint());
    }

    private RunEvidence evidenceWithHeader(String name, String value) {
        EffectiveTestPlan base = Fixtures.plan();
        EffectiveTestPlan compromised = new EffectiveTestPlan(base.id(), base.projectId(),
                base.projectName(), base.serviceVersion(), base.intent(), base.workloadName(),
                base.workloadDescription(), base.testType(), base.workloadModel(),
                base.peakLevel(), base.stages(), base.operations(), base.workloadSource(),
                base.thresholds(), base.environmentName(), base.environmentType(),
                dev.vortex.core.environment.TargetUrl.of("http://localhost:8080"),
                dev.vortex.core.environment.TargetUrl.of("https://admin:hunter2@checkout.internal"),
                base.targetRewriteReason(), base.dependencyMode(), base.classification(),
                Map.of(name, value), base.k6Options(), base.runner(), base.scriptSource(),
                base.safetyDecisions(), base.fingerprint());

        return evidenceFor(compromised, Map.of(name, value));
    }

    private RunEvidence evidenceFor(EffectiveTestPlan compromised, Map<String, String> unusedHeaders) {
        MeasuredResults results = Fixtures.results(281, 0.0008);
        ThresholdEvaluation evaluation =
                new ThresholdEvaluator().evaluate(Fixtures.thresholds(), results);

        TestExecution execution = new TestExecution(
                ExecutionId.of("leak0001"), compromised.projectId(), compromised,
                ExecutionState.COMPLETED, Fixtures.NOW, Fixtures.NOW.plusSeconds(1),
                Fixtures.NOW.plusSeconds(601), results,
                new DeterministicSummary("Does it hold?", evaluation.overall(), "Yes.",
                        results, evaluation, null, null, List.of()),
                ToolVersions.unknown(),
                ExecutionArtifacts.empty().with("plan.json", "plan.json"), null, "");

        return new RunEvidenceService(
                new DeterministicAnalyzer(new ThresholdEvaluator(), new BreakpointDetector(),
                        new SystemSaturationDetector()),
                new FindingDetector(), new EvidenceSanitizer(), new RegressionEvaluator(),
                Clock.fixed(EvidenceFixtures.GENERATED_AT))
                .assemble(execution, "/tmp/executions/leak0001", List.of("plan.json"));
    }
}
