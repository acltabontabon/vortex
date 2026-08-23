package com.acltabontabon.vortex.k6;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.metrics.FailureClass;
import com.acltabontabon.vortex.core.metrics.ResponseClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The engine's vocabulary stops here.
 *
 * <p>These assertions are about the mapping, not about HTTP: what matters is that each band k6
 * documents lands in the class a conclusion would draw from it, and that a code from no band lands
 * nowhere in particular. A failure attributed to the wrong cause is worse than one attributed to
 * none, because only the second is visibly a gap.
 */
class K6OutcomeClassifierTest {

    @Nested
    @DisplayName("when the service answered")
    class Answered {

        @Test
        @DisplayName("the class of answer is carried, and only errors count as failures")
        void answersAreClassifiedByFamily() {
            assertThat(K6OutcomeClassifier.classify("200", "", true).responseClass())
                    .isEqualTo(ResponseClass.SUCCESS);
            assertThat(K6OutcomeClassifier.classify("301", "", true).responseClass())
                    .isEqualTo(ResponseClass.REDIRECT);
            assertThat(K6OutcomeClassifier.classify("200", "", true).isFailure()).isFalse();
        }

        @Test
        @DisplayName("an unexpected error response is an application failure, never a transport one")
        void errorResponsesAreApplicationFailures() {
            var serverError = K6OutcomeClassifier.classify("503", "", false);

            // A response arrived, so no transport-level explanation can apply however the run also
            // happened to fail elsewhere.
            assertThat(serverError.responseClass()).isEqualTo(ResponseClass.SERVER_ERROR);
            assertThat(serverError.failureClass()).isEqualTo(FailureClass.APPLICATION);
        }

        @Test
        @DisplayName("a status the workload declared as expected is not a failure")
        void declaredExpectationsAreNotFailures() {
            var expected = K6OutcomeClassifier.classify("404", "", true);

            // http.expectedStatuses in the generated script means exactly this, and k6's own error
            // rate is computed from the same tag. Disagreeing with it would put two different
            // failure counts on one page.
            assertThat(expected.responseClass()).isEqualTo(ResponseClass.CLIENT_ERROR);
            assertThat(expected.isFailure()).isFalse();
        }
    }

    @Nested
    @DisplayName("when no answer arrived")
    class Unanswered {

        @Test
        @DisplayName("each documented band lands in the class a conclusion would draw from it")
        void bandsMapToCauses() {
            assertThat(failureFor("1050")).isEqualTo(FailureClass.TIMEOUT);
            assertThat(failureFor("1211")).isEqualTo(FailureClass.TIMEOUT);
            assertThat(failureFor("1101")).isEqualTo(FailureClass.CONNECTION);
            assertThat(failureFor("1212")).isEqualTo(FailureClass.CONNECTION);
            assertThat(failureFor("1301")).isEqualTo(FailureClass.TRANSPORT);
            assertThat(failureFor("1501")).isEqualTo(FailureClass.TRANSPORT);
            assertThat(failureFor("1701")).isEqualTo(FailureClass.TRANSPORT);
        }

        @Test
        @DisplayName("a code from no band is unclassified, not folded into the nearest plausible one")
        void unknownCodesAreNotGuessed() {
            assertThat(failureFor("9999")).isEqualTo(FailureClass.UNKNOWN);
            assertThat(failureFor("1000")).isEqualTo(FailureClass.UNKNOWN);
            assertThat(failureFor("")).isEqualTo(FailureClass.UNKNOWN);
            assertThat(failureFor("not-a-number")).isEqualTo(FailureClass.UNKNOWN);
        }

        @Test
        @DisplayName("a request with no response is never counted as an answer of any class")
        void noResponseIsNotAResponse() {
            var outcome = K6OutcomeClassifier.classify("0", "1212", false);

            assertThat(outcome.responseClass()).isEqualTo(ResponseClass.UNKNOWN);
            assertThat(outcome.responseClass().isFailure()).isFalse();
            assertThat(outcome.isFailure()).isTrue();
        }

        @Test
        @DisplayName("a declared expectation cannot excuse a request that never reached the service")
        void expectationsDoNotExcuseTransportFailures() {
            // expected_response is about the status a service returned. There is no status here,
            // so the tag has nothing to say and must not suppress the failure.
            assertThat(K6OutcomeClassifier.classify("0", "1212", true).isFailure()).isTrue();
        }
    }

    private static FailureClass failureFor(String errorCode) {
        return K6OutcomeClassifier.classify("0", errorCode, false).failureClass();
    }
}
