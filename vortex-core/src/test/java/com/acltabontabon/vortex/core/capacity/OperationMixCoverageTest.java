package com.acltabontabon.vortex.core.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acltabontabon.vortex.core.shared.OperationId;
import com.acltabontabon.vortex.core.workload.OperationMix;
import com.acltabontabon.vortex.core.workload.WeightedOperation;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Narrowing the evidence is acceptable; overstating its completeness is not.
 *
 * <p>This is the property the whole type exists for, and it is worth stating as a test rather than
 * only as a comment: a mix normalises its weights, and if nothing recorded what share of production
 * those weights describe, a partial view silently becomes the definition of the whole.
 */
class OperationMixCoverageTest {

    private static final OperationId GET_ORDER = OperationId.of("getOrder");
    private static final OperationId CREATE_ORDER = OperationId.of("createOrder");

    @Nested
    @DisplayName("a partial match")
    class Partial {

        private final OperationMix matchedShape = OperationMix.of(List.of(
                WeightedOperation.of(GET_ORDER, 50_000),
                WeightedOperation.of(CREATE_ORDER, 30_000)));

        private final OperationMixCoverage coverage =
                new OperationMixCoverage(100_000, 80_000);

        @Test
        void normalisesTheShapeAsAMixMust() {
            assertThat(matchedShape.sharePercent(GET_ORDER)).isEqualTo("62.5");
            assertThat(matchedShape.sharePercent(CREATE_ORDER)).isEqualTo("37.5");
        }

        @Test
        void butRetainsWhatShareOfProductionItActuallyDescribes() {
            assertThat(coverage.coverage()).isEqualTo(0.8);
            assertThat(coverage.matchedRequests()).isEqualTo(80_000);
            assertThat(coverage.totalObservedRequests()).isEqualTo(100_000);
            assertThat(coverage.unmatchedRequests()).isEqualTo(20_000);
        }

        @Test
        void andSaysSoInWordsAReaderCanCheck() {
            assertThat(coverage.describe())
                    .contains("80")
                    .contains("20000")
                    .contains("100000");
        }

        @Test
        void isRepresentativeButNotComplete() {
            assertThat(coverage.isRepresentative()).isTrue();
            assertThat(coverage.isComplete()).isFalse();
        }
    }

    @Nested
    @DisplayName("the thresholds")
    class Thresholds {

        @Test
        void aFullMatchIsComplete() {
            assertThat(new OperationMixCoverage(1000, 1000).isComplete()).isTrue();
        }

        @Test
        void aRoundingRemainderStillCountsAsComplete() {
            assertThat(new OperationMixCoverage(1000, 995).isComplete()).isTrue();
        }

        @Test
        void halfOfProductionIsNotRepresentative() {
            var thin = new OperationMixCoverage(1000, 500);

            assertThat(thin.isRepresentative()).isFalse();
            assertThat(thin.isComplete()).isFalse();
        }

        @Test
        void nothingObservedIsNotSilentlyFullCoverage() {
            var nothing = new OperationMixCoverage(0, 0);

            assertThat(nothing.coverage()).isZero();
            assertThat(nothing.isComplete()).isFalse();
            assertThat(nothing.describe()).contains("No requests were observed");
        }
    }

    @Nested
    @DisplayName("what it refuses to hold")
    class Invariants {

        @Test
        void moreMatchedThanObservedIsImpossible() {
            assertThatThrownBy(() -> new OperationMixCoverage(100, 200))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot exceed");
        }

        @Test
        void negativeCountsAreRejected() {
            assertThatThrownBy(() -> new OperationMixCoverage(-1, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
