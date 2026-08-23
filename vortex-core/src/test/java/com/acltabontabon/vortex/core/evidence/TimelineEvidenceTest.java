package com.acltabontabon.vortex.core.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.analysis.StageObservation;
import com.acltabontabon.vortex.core.metrics.SamplePoint;
import com.acltabontabon.vortex.core.shared.ErrorRate;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where a breakpoint marker belongs on a shared time axis: the real bucket time the first
 * non-compliant stage began, not a fraction a renderer would have to reinterpret.
 */
class TimelineEvidenceTest {

    private static final Instant START = Instant.parse("2026-08-21T10:00:00Z");
    private static final Duration BUCKET = Duration.ofSeconds(5);

    @Test
    @DisplayName("lands on the first sample of the stage that first violated a threshold")
    void marksWhereComplianceWasFirstLost() {
        List<SamplePoint> points = points(6);
        List<StageObservation> stages = List.of(
                stage(3, List.of()),
                stage(3, List.of("p95 latency below 200 ms")));

        Instant breakpoint = TimelineEvidence.breakpointInstant(stages, points);

        // The second stage's first sample — index 3, since the first stage consumed 3 buckets.
        assertThat(breakpoint).isEqualTo(points.get(3).at());
    }

    @Test
    @DisplayName("the very first stage can itself be the violation")
    void firstStageCanBeTheBreakpoint() {
        List<SamplePoint> points = points(4);
        List<StageObservation> stages = List.of(
                stage(2, List.of("error rate below 5%")),
                stage(2, List.of()));

        assertThat(TimelineEvidence.breakpointInstant(stages, points)).isEqualTo(points.get(0).at());
    }

    @Test
    @DisplayName("no marker when every stage complied")
    void nullWhenNothingWasViolated() {
        List<SamplePoint> points = points(4);
        List<StageObservation> stages = List.of(stage(2, List.of()), stage(2, List.of()));

        assertThat(TimelineEvidence.breakpointInstant(stages, points)).isNull();
    }

    @Test
    @DisplayName("no marker from a single stage — there is no boundary to place it at")
    void nullWithFewerThanTwoStages() {
        List<SamplePoint> points = points(4);
        List<StageObservation> stages = List.of(stage(4, List.of("p95 latency below 200 ms")));

        assertThat(TimelineEvidence.breakpointInstant(stages, points)).isNull();
    }

    @Test
    @DisplayName("no marker without a series to place it on")
    void nullWithoutPoints() {
        List<StageObservation> stages = List.of(
                stage(2, List.of()),
                stage(2, List.of("p95 latency below 200 ms")));

        assertThat(TimelineEvidence.breakpointInstant(stages, List.of())).isNull();
    }

    @Test
    @DisplayName("levelChangeInstant lands on the second stage's first sample, compliant or not")
    void levelChangeMarksTheSecondStage() {
        List<SamplePoint> points = points(6);
        List<StageObservation> stages = List.of(
                stage(3, List.of()),
                stage(3, List.of()));

        // Unlike breakpointInstant, this needs no violation at all — a spike that never breached an
        // objective still jumped.
        assertThat(TimelineEvidence.levelChangeInstant(stages, points)).isEqualTo(points.get(3).at());
    }

    @Test
    @DisplayName("levelChangeInstant ignores compliance entirely — only stage order matters")
    void levelChangeIgnoresCompliance() {
        List<SamplePoint> points = points(5);
        List<StageObservation> stages = List.of(
                stage(2, List.of("p95 latency below 200 ms")),
                stage(3, List.of()));

        assertThat(TimelineEvidence.levelChangeInstant(stages, points)).isEqualTo(points.get(2).at());
    }

    @Test
    @DisplayName("no levelChangeInstant from a single stage — there is no second one to move to")
    void nullLevelChangeWithFewerThanTwoStages() {
        List<SamplePoint> points = points(4);
        List<StageObservation> stages = List.of(stage(4, List.of()));

        assertThat(TimelineEvidence.levelChangeInstant(stages, points)).isNull();
    }

    @Test
    @DisplayName("no levelChangeInstant without a series to place it on")
    void nullLevelChangeWithoutPoints() {
        List<StageObservation> stages = List.of(stage(2, List.of()), stage(2, List.of()));

        assertThat(TimelineEvidence.levelChangeInstant(stages, List.of())).isNull();
    }

    private static List<SamplePoint> points(int count) {
        List<SamplePoint> points = new ArrayList<>();
        Instant cursor = START;
        for (int i = 0; i < count; i++) {
            points.add(new SamplePoint(cursor, BUCKET, RequestsPerSecond.of(10),
                    ErrorRate.ofFraction(0), Duration.ofMillis(100), null));
            cursor = cursor.plus(BUCKET);
        }
        return points;
    }

    private static StageObservation stage(int sampleCount, List<String> violatedThresholds) {
        return new StageObservation(RequestsPerSecond.of(10), RequestsPerSecond.of(10),
                Duration.ofMillis(100), ErrorRate.ofFraction(0), sampleCount, violatedThresholds);
    }
}
