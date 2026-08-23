package com.acltabontabon.vortex.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.metrics.MeasuredResults;
import com.acltabontabon.vortex.core.metrics.MetricSeries;
import com.acltabontabon.vortex.core.metrics.SamplePoint;
import com.acltabontabon.vortex.core.validity.RunQuality;
import com.acltabontabon.vortex.core.validity.RunQualityAssessment;
import com.acltabontabon.vortex.core.validity.ValidityEffect;
import com.acltabontabon.vortex.core.validity.ValidityFinding;
import com.acltabontabon.vortex.core.validity.ValidityReason;
import com.acltabontabon.vortex.core.shared.ErrorRate;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Executions recorded before Phase 4 must still be readable, and must not acquire numbers.
 *
 * <p>Measurements are stored as an opaque JSON document, so widening {@link MeasuredResults} is a
 * compatibility question rather than a schema one. The failure mode is quiet: a run recorded last
 * month deserializes into a record whose new components default to zero, and Vortex then reports
 * that its generator dropped no work — a fact nobody measured, standing behind a capacity claim.
 *
 * <h2>Why the fixture is derived rather than hand-written</h2>
 * These tests serialize a current document and <em>remove</em> the components Phase 4 added, which
 * is exactly the shape a pre-Phase-4 row has. Hand-writing the JSON would mean guessing the wire
 * form of a sealed {@code LoadLevel} and would quietly stop testing anything the day that form
 * changed for an unrelated reason.
 */
class PhaseFourCompatibilityTest {

    /** The components Phase 4 added to the stored measurement document. */
    private static final List<String> ADDED_BY_PHASE_FOUR =
            List.of("generation", "phases", "reliability");

    private static ObjectNode withoutPhaseFourFields(Object document) {
        ObjectNode node = JsonDocuments.mapper().valueToTree(document);
        ADDED_BY_PHASE_FOUR.forEach(node::remove);
        return node;
    }

    @Test
    @DisplayName("a measurement document written before Phase 4 still reads")
    void oldMeasurementsStillRead() throws Exception {
        ObjectNode before = withoutPhaseFourFields(Fixtures.results(420, 0.004));

        MeasuredResults restored =
                JsonDocuments.mapper().treeToValue(before, MeasuredResults.class);

        assertThat(restored.requests()).isPositive();
        assertThat(restored.latency().isEmpty()).isFalse();
    }

    @Test
    @DisplayName("and reports the generator as unmeasured rather than as having kept up")
    void oldMeasurementsDoNotAcquireAGeneratorVerdict() throws Exception {
        ObjectNode before = withoutPhaseFourFields(Fixtures.results(420, 0.004));

        MeasuredResults restored =
                JsonDocuments.mapper().treeToValue(before, MeasuredResults.class);

        // The distinction the whole phase rests on. Nobody asked this run whether its generator
        // kept up, so nothing may answer on its behalf.
        assertThat(restored.generation().wasReported()).isFalse();
        assertThat(restored.generation().iterationsDroppedIfPresent()).isEmpty();
        assertThat(restored.generation().droppedWork()).isFalse();
        assertThat(restored.generation().droppedFraction()).isEmpty();
    }

    @Test
    @DisplayName("and reports no phase breakdown and no outcome classification, rather than empty ones")
    void oldMeasurementsDoNotAcquireOutcomes() throws Exception {
        ObjectNode before = withoutPhaseFourFields(Fixtures.results(420, 0.004));

        MeasuredResults restored =
                JsonDocuments.mapper().treeToValue(before, MeasuredResults.class);

        assertThat(restored.phases().isEmpty()).isTrue();
        assertThat(restored.reliability().wasReported()).isFalse();
        assertThat(restored.reliability().unreachedShare()).isEmpty();
    }

    @Test
    @DisplayName("a bucket written before Phase 4 reports no dropped work, rather than none dropped")
    void oldBucketsDoNotAcquireDropCounts() throws Exception {
        SamplePoint current = new SamplePoint(
                Instant.parse("2026-08-21T10:00:00Z"), Duration.ofSeconds(5),
                RequestsPerSecond.of(420), ErrorRate.ofFraction(0.004),
                Duration.ofMillis(180), RequestsPerSecond.of(500), 40, 12L);
        ObjectNode before = JsonDocuments.mapper().valueToTree(current);
        before.remove("iterationsDropped");

        SamplePoint restored = JsonDocuments.mapper().treeToValue(before, SamplePoint.class);

        assertThat(restored.iterationsDroppedIfPresent()).isEmpty();
        assertThat(restored.at()).isEqualTo(current.at());
    }

    @Test
    @DisplayName("what Phase 4 measured survives the database unchanged")
    void newMeasurementsRoundTrip() throws Exception {
        MeasuredResults measured = Fixtures.results(420, 0.004);
        MeasuredResults withGeneratorEvidence = new MeasuredResults(
                measured.window(), measured.targetLoad(), measured.achievedRate(),
                measured.requests(), measured.failures(), measured.latency(),
                measured.perOperation(), measured.series(), measured.observations(),
                measured.stageTelemetry(), measured.telemetryGaps(),
                new com.acltabontabon.vortex.core.metrics.LoadGeneration(900L, 412L, 15.0),
                measured.phases(), measured.reliability());

        JsonNode document = JsonDocuments.mapper().valueToTree(withGeneratorEvidence);
        MeasuredResults restored =
                JsonDocuments.mapper().treeToValue(document, MeasuredResults.class);

        assertThat(restored.generation().iterationsDroppedIfPresent()).hasValue(412L);
        assertThat(restored.generation().droppedWork()).isTrue();
    }

    @Test
    @DisplayName("an execution recorded before validity existed reads back as not assessed")
    void oldExecutionsAreNotAssessedRatherThanValid() throws Exception {
        // The migration defaults run_quality to NOT_ASSESSED rather than VALID, because a row
        // written before this axis existed was never graded. Defaulting to VALID would manufacture
        // a judgement nobody made, standing behind a capacity figure — which is the exact failure
        // the axis exists to prevent, reintroduced through a schema default.
        ObjectNode before = JsonDocuments.mapper().valueToTree(RunQualityAssessment.notAssessed());

        RunQualityAssessment restored =
                JsonDocuments.mapper().treeToValue(before, RunQualityAssessment.class);

        assertThat(restored.quality()).isEqualTo(RunQuality.NOT_ASSESSED);
        assertThat(restored.quality().isAssessed()).isFalse();
        // It carries no reason codes, so it withholds nothing: an older run's page gains a note
        // rather than losing a number.
        assertThat(restored.permitsAnyCapacityClaim()).isTrue();
        assertThat(restored.qualifications()).isEmpty();
    }

    @Test
    @DisplayName("an assessment survives the database with its findings and their sentences intact")
    void assessmentsRoundTrip() throws Exception {
        RunQualityAssessment assessed = RunQualityAssessment.of(List.of(new ValidityFinding(
                ValidityReason.OFFERED_LOAD_NOT_GENERATED, ValidityEffect.WITHHOLDS_CAPACITY,
                "The load generator could not start 4812 units of work it was asked to start.",
                List.of("metric:generator.iterations.dropped"))));

        RunQualityAssessment restored = JsonDocuments.mapper().treeToValue(
                JsonDocuments.mapper().valueToTree(assessed), RunQualityAssessment.class);

        assertThat(restored.quality()).isEqualTo(RunQuality.INVALID);
        assertThat(restored.has(ValidityReason.OFFERED_LOAD_NOT_GENERATED)).isTrue();
        // The sentence is the part a reader argues with, so it has to survive storage intact.
        assertThat(restored.qualifications()).singleElement()
                .satisfies(statement -> assertThat(statement).contains("4812"));
        assertThat(restored.findings()).singleElement().satisfies(finding ->
                assertThat(finding.evidenceIds()).containsExactly(
                        "metric:generator.iterations.dropped"));
    }

    @Test
    @DisplayName("a zero drop count survives as zero, and never degrades into 'not measured'")
    void zeroIsNotAbsence() throws Exception {
        // The mirror of every other test here. Serialization drops nulls, so the risk runs both
        // ways: a run that genuinely reported no drops must keep saying so, because that is the
        // evidence a capacity claim is allowed to rest on.
        MeasuredResults measured = Fixtures.results(420, 0.004);
        MeasuredResults keptUp = new MeasuredResults(
                measured.window(), measured.targetLoad(), measured.achievedRate(),
                measured.requests(), measured.failures(), measured.latency(),
                measured.perOperation(), MetricSeries.empty(), measured.observations(),
                measured.stageTelemetry(), measured.telemetryGaps(),
                new com.acltabontabon.vortex.core.metrics.LoadGeneration(900L, 0L, 15.0),
                measured.phases(), measured.reliability());

        MeasuredResults restored = JsonDocuments.mapper().treeToValue(
                JsonDocuments.mapper().valueToTree(keptUp), MeasuredResults.class);

        assertThat(restored.generation().iterationsDroppedIfPresent()).hasValue(0L);
        assertThat(restored.generation().wasReported()).isTrue();
        assertThat(restored.generation().droppedWork()).isFalse();
    }
}
