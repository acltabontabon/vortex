package com.acltabontabon.vortex.core.comparison;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.validity.RunQualityAssessment;
import com.acltabontabon.vortex.core.validity.ValidityEffect;
import com.acltabontabon.vortex.core.validity.ValidityFinding;
import com.acltabontabon.vortex.core.validity.ValidityReason;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What a baseline's own validity allows a comparison to conclude.
 *
 * <p>Both directions matter, and the positive one matters more. A blanket refusal of degraded
 * baselines would leave almost every team with none - partial telemetry is the normal case - so the
 * tests below assert as carefully that the allowed deltas survive as that the prohibited ones do
 * not.
 */
class BaselineEligibilityTest {

    private static RunQualityAssessment carrying(ValidityReason reason, ValidityEffect effect) {
        return RunQualityAssessment.of(List.of(
                new ValidityFinding(reason, effect, "something measurable happened", List.of())));
    }

    @Nested
    @DisplayName("a baseline that did not measure what it claims to")
    class Invalid {

        private final BaselineEligibility eligibility = BaselineEligibility.of(carrying(
                ValidityReason.OFFERED_LOAD_NOT_GENERATED, ValidityEffect.WITHHOLDS_CAPACITY));

        @Test
        @DisplayName("is not offered as a baseline at all")
        void isNotOffered() {
            assertThat(eligibility.offeredAsBaseline()).isFalse();
        }

        @Test
        @DisplayName("and says why, rather than simply producing nothing")
        void saysWhy() {
            assertThat(eligibility.reason()).contains("did not measure what it claims to");
        }

        @Test
        @DisplayName("permitting no conclusion of any kind")
        void permitsNothing() {
            for (DeltaKind kind : DeltaKind.values()) {
                assertThat(eligibility.permits(kind)).as("%s", kind).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("a baseline with incomplete telemetry")
    class TelemetryIncomplete {

        private final BaselineEligibility eligibility = BaselineEligibility.of(carrying(
                ValidityReason.TELEMETRY_INCOMPLETE, ValidityEffect.QUALIFIES));

        @Test
        @DisplayName("is still a baseline, because most runs have partial telemetry")
        void isStillOffered() {
            assertThat(eligibility.offeredAsBaseline()).isTrue();
        }

        @Test
        @DisplayName("and latency, throughput and reliability still stand")
        void theMeasurementsStillStand() {
            // The positive half. These were measured perfectly well; only what the service was
            // doing underneath them is missing.
            assertThat(eligibility.permits(DeltaKind.LATENCY)).isTrue();
            assertThat(eligibility.permits(DeltaKind.THROUGHPUT)).isTrue();
            assertThat(eligibility.permits(DeltaKind.RELIABILITY)).isTrue();
        }

        @Test
        @DisplayName("while resource and efficiency deltas are refused, with a reason")
        void resourceDeltasAreRefused() {
            assertThat(eligibility.permits(DeltaKind.RESOURCE)).isFalse();
            assertThat(eligibility.permits(DeltaKind.EFFICIENCY)).isFalse();
            assertThat(eligibility.reason()).contains("resource and efficiency");
        }
    }

    @Nested
    @DisplayName("a baseline whose stages were too thin")
    class InsufficientSamples {

        private final BaselineEligibility eligibility = BaselineEligibility.of(carrying(
                ValidityReason.INSUFFICIENT_SAMPLES, ValidityEffect.QUALIFIES));

        @Test
        @DisplayName("refuses capacity and breakpoint movement only")
        void refusesBoundaryConclusions() {
            assertThat(eligibility.permits(DeltaKind.CAPACITY)).isFalse();
            assertThat(eligibility.permits(DeltaKind.BREAKPOINT_MOVEMENT)).isFalse();
            assertThat(eligibility.permits(DeltaKind.LATENCY)).isTrue();
            assertThat(eligibility.permits(DeltaKind.RESOURCE)).isTrue();
        }
    }

    @Test
    @DisplayName("a run too short refuses capacity movement while latency deltas stand")
    void tooShortRefusesCapacityOnly() {
        var eligibility = BaselineEligibility.of(
                carrying(ValidityReason.RUN_TOO_SHORT, ValidityEffect.QUALIFIES));

        assertThat(eligibility.permits(DeltaKind.CAPACITY)).isFalse();
        assertThat(eligibility.permits(DeltaKind.LATENCY)).isTrue();
    }

    @Test
    @DisplayName("a valid baseline is unrestricted")
    void aValidBaselineIsUnrestricted() {
        var eligibility = BaselineEligibility.of(RunQualityAssessment.valid());

        assertThat(eligibility.isUnrestricted()).isTrue();
        assertThat(eligibility.refusedDeltas()).isEmpty();
    }

    @Test
    @DisplayName("a baseline recorded before validity existed is unrestricted, not refused")
    void anUnassessedBaselineIsUnrestricted() {
        // NOT_ASSESSED carries no reason codes, so there is nothing for it to undermine. Treating
        // it as suspect would retroactively invalidate every comparison against a historical run.
        var eligibility = BaselineEligibility.of(RunQualityAssessment.notAssessed());

        assertThat(eligibility.offeredAsBaseline()).isTrue();
        assertThat(eligibility.isUnrestricted()).isTrue();
    }
}
