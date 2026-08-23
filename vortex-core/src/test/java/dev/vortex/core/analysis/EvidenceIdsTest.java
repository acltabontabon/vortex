package dev.vortex.core.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.metrics.LoadGeneration;
import dev.vortex.core.metrics.MeasuredResults;
import dev.vortex.core.shared.Percentile;
import java.lang.reflect.Modifier;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The vocabulary a finding is allowed to cite.
 *
 * <p>Two properties are guarded here, and the second is the reason the first is awkward. Citation
 * identifiers must not name a transport, because Phase 7 populates the same measurements from a
 * queue. But they are also persisted inside stored findings, so renaming one is a data migration
 * wearing a rename's clothes.
 */
class EvidenceIdsTest {

    @Nested
    @DisplayName("identifiers name a measurement, not a transport")
    class TransportNeutral {

        @Test
        @DisplayName("no published identifier contains a protocol name")
        void identifiersDoNotNameATransport() throws Exception {
            // ArchUnit checks type names; a constant's *value* is invisible to it, and these
            // values are exactly where the transport used to be spelled out.
            for (var field : EvidenceIds.class.getDeclaredFields()) {
                if (!Modifier.isPublic(field.getModifiers()) || field.getType() != String.class) {
                    continue;
                }
                String value = ((String) field.get(null)).toLowerCase(Locale.ROOT);
                assertThat(value).as("published identifier %s", field.getName())
                        .doesNotContain("http", "grpc", "kafka", "amqp", "sqs");
            }
        }

        @Test
        @DisplayName("a latency identifier names the percentile, not the protocol")
        void latencyIdentifiersAreNeutral() {
            assertThat(EvidenceIds.latency(Percentile.P95)).isEqualTo("metric:latency.p95");
        }
    }

    @Nested
    @DisplayName("citations written under an older name still resolve")
    class Renames {

        @Test
        @DisplayName("each renamed identifier maps to the one that replaced it")
        void renamedIdentifiersResolve() {
            assertThat(EvidenceIds.resolve("metric:http.errorRate"))
                    .isEqualTo(EvidenceIds.REQUEST_ERROR_RATE);
            assertThat(EvidenceIds.resolve("metric:http.requests"))
                    .isEqualTo(EvidenceIds.REQUEST_COUNT);
            assertThat(EvidenceIds.resolve("metric:http.failures"))
                    .isEqualTo(EvidenceIds.REQUEST_FAILURES);
            assertThat(EvidenceIds.resolve("metric:http.rate.achieved"))
                    .isEqualTo(EvidenceIds.THROUGHPUT_ACHIEVED);
            assertThat(EvidenceIds.resolve("metric:http.latency.p99"))
                    .isEqualTo(EvidenceIds.latency(Percentile.P99));
        }

        @Test
        @DisplayName("an identifier that was never renamed passes through untouched")
        void unrenamedIdentifiersArePreserved() {
            assertThat(EvidenceIds.resolve(EvidenceIds.THROUGHPUT_TARGET))
                    .isEqualTo(EvidenceIds.THROUGHPUT_TARGET);
            assertThat(EvidenceIds.resolve("threshold:latency-p95")).isEqualTo("threshold:latency-p95");
        }

        @Test
        @DisplayName("every rename points at an identifier the run can actually offer")
        void renamesPointAtLiveIdentifiers() {
            // A rename that resolved to a name nothing publishes would discard the finding just as
            // silently as no rename at all.
            var available = EvidenceIds.measurements(Fixtures.results(420, 0.004)).keySet();

            assertThat(available).contains(
                    EvidenceIds.resolve("metric:http.errorRate"),
                    EvidenceIds.resolve("metric:http.requests"),
                    EvidenceIds.resolve("metric:http.failures"));
        }
    }

    @Nested
    @DisplayName("what the generator did is citable only when it was measured")
    class GeneratorEvidence {

        @Test
        @DisplayName("a run that reported dropped work offers it as evidence")
        void droppedWorkIsCitable() {
            MeasuredResults measured = withGeneration(new LoadGeneration(900L, 412L, 15.0));

            assertThat(EvidenceIds.measurements(measured))
                    .containsEntry(EvidenceIds.ITERATIONS_DROPPED, "412");
        }

        @Test
        @DisplayName("a run that reported none offers nothing to cite, rather than a zero")
        void unmeasuredGeneratorEvidenceIsNotOffered() {
            // If this appeared as "0", a finding could cite it and conclude the generator kept up
            // from a measurement nobody took.
            var measurements = EvidenceIds.measurements(Fixtures.results(420, 0.004));

            assertThat(measurements).doesNotContainKey(EvidenceIds.ITERATIONS_DROPPED);
            assertThat(measurements).doesNotContainKey(EvidenceIds.ITERATIONS_STARTED);
        }

        private MeasuredResults withGeneration(LoadGeneration generation) {
            MeasuredResults base = Fixtures.results(420, 0.004);
            return new MeasuredResults(base.window(), base.targetLoad(), base.achievedRate(),
                    base.requests(), base.failures(), base.latency(), base.perOperation(),
                    base.series(), base.observations(), base.stageTelemetry(), base.telemetryGaps(),
                    generation, base.phases(), base.reliability());
        }
    }
}
