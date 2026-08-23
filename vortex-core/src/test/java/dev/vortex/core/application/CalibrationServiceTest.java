package dev.vortex.core.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.capacity.ObservationSource;
import dev.vortex.core.capacity.ProductionObservation;
import dev.vortex.core.metrics.TimeWindow;
import dev.vortex.core.catalog.ServiceCatalog;
import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.port.Clock;
import dev.vortex.core.port.ProductionObservationSource;
import dev.vortex.core.project.ProjectConfiguration;
import dev.vortex.core.shared.RequestsPerSecond;
import dev.vortex.core.workload.Observation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Which source answers, over what window, and what happens when nothing can. */
class CalibrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");

    private static final ObservationSource PROMETHEUS = new ObservationSource(
            ObservationSource.Kind.PROMETHEUS, "http://prometheus.internal:9090",
            "checkout-service", Duration.ofDays(30), Map.of(), Map.of());

    private static final ObservationSource DYNATRACE = new ObservationSource(
            ObservationSource.Kind.DYNATRACE, "https://abc12345.live.dynatrace.com",
            "SERVICE-1A2B3C4D5E6F7890", Duration.ofDays(30), Map.of(), Map.of());

    private static ServiceCatalog catalog() {
        return Fixtures.catalog();
    }

    /** Records the request it was given and answers with a fixed observation. */
    private static final class RecordingSource implements ProductionObservationSource {

        private final ObservationSource.Kind kind;
        private final AtomicReference<ObservationRequest> seen = new AtomicReference<>();
        private final AtomicReference<TimeWindow> verified = new AtomicReference<>();

        RecordingSource(ObservationSource.Kind kind) {
            this.kind = kind;
        }

        @Override
        public String id() {
            return kind.name().toLowerCase(java.util.Locale.ROOT);
        }

        @Override
        public boolean supports(ObservationSource source) {
            return source.kind() == kind;
        }

        @Override
        public Retrieval retrieve(ObservationRequest request) {
            seen.set(request);
            return new Retrieved(new ProductionObservation(null, null, RequestsPerSecond.of(180),
                    null, "", Observation.unknown(), ""));
        }

        @Override
        public Retrieval verify(ObservationSource source, TimeWindow window, Duration resolution) {
            verified.set(window);
            return new Retrieved(new ProductionObservation(null, null, RequestsPerSecond.of(180),
                    null, "", Observation.unknown(), ""));
        }
    }

    private ProjectConfiguration configuredWith(ObservationSource source) {
        return Fixtures.configuration().withObservationSource(source);
    }

    @Nested
    @DisplayName("choosing a source")
    class Choosing {

        @Test
        void theConfiguredKindDecidesWhichAdapterAnswers() {
            var prometheus = new RecordingSource(ObservationSource.Kind.PROMETHEUS);
            var dynatrace = new RecordingSource(ObservationSource.Kind.DYNATRACE);
            var service = new CalibrationService(List.of(prometheus, dynatrace), Clock.fixed(NOW));

            service.fetch(configuredWith(DYNATRACE), catalog(), null);

            assertThat(dynatrace.seen.get()).isNotNull();
            assertThat(prometheus.seen.get())
                    .as("the adapter that does not support this kind is never asked")
                    .isNull();
        }

        @Test
        void aKindWithNoAdapterInThisBuildIsRefusedWithARemedy() {
            var service = new CalibrationService(
                    List.of(new RecordingSource(ObservationSource.Kind.PROMETHEUS)),
                    Clock.fixed(NOW));

            var result = service.fetch(configuredWith(DYNATRACE), catalog(), null);

            assertThat(result).isInstanceOfSatisfying(ProductionObservationSource.NotRetrieved.class,
                    failure -> {
                        assertThat(failure.why()).contains("Dynatrace");
                        assertThat(failure.remedy()).isNotBlank();
                    });
        }
    }

    @Nested
    @DisplayName("the window and its resolution")
    class Window {

        @Test
        void theConfiguredWindowEndsNowAndStretchesBack() {
            var source = new RecordingSource(ObservationSource.Kind.PROMETHEUS);
            new CalibrationService(List.of(source), Clock.fixed(NOW))
                    .fetch(configuredWith(PROMETHEUS), catalog(), null);

            assertThat(source.seen.get().window().end()).isEqualTo(NOW);
            assertThat(source.seen.get().window().start()).isEqualTo(NOW.minus(Duration.ofDays(30)));
        }

        @Test
        void anOverrideReplacesTheConfiguredWindow() {
            var source = new RecordingSource(ObservationSource.Kind.PROMETHEUS);
            new CalibrationService(List.of(source), Clock.fixed(NOW))
                    .fetch(configuredWith(PROMETHEUS), catalog(), Duration.ofHours(6));

            assertThat(source.seen.get().window().start()).isEqualTo(NOW.minus(Duration.ofHours(6)));
        }

        @Test
        void theResolutionFollowsTheDocumentedRuleRatherThanTheAdapterSChoice() {
            var source = new RecordingSource(ObservationSource.Kind.PROMETHEUS);
            var service = new CalibrationService(List.of(source), Clock.fixed(NOW));

            service.fetch(configuredWith(PROMETHEUS), catalog(), Duration.ofHours(6));
            assertThat(source.seen.get().resolution()).isEqualTo(Duration.ofMinutes(1));

            service.fetch(configuredWith(PROMETHEUS), catalog(), Duration.ofDays(30));
            assertThat(source.seen.get().resolution()).isEqualTo(Duration.ofHours(1));
        }
    }

    @Nested
    @DisplayName("describing the operations to ask about")
    class Operations {

        @Test
        void everyCatalogOperationIsNamedByMethodAndPathTemplate() {
            var source = new RecordingSource(ObservationSource.Kind.PROMETHEUS);
            new CalibrationService(List.of(source), Clock.fixed(NOW))
                    .fetch(configuredWith(PROMETHEUS), catalog(), null);

            assertThat(source.seen.get().operations())
                    .extracting(operation -> operation.method() + " " + operation.pathTemplate())
                    .contains("GET /orders/{id}", "POST /orders", "GET /accounts/{id}");
        }

        @Test
        void theOperationIdTravelsSoTheAnswerCanBeAttributedBack() {
            var source = new RecordingSource(ObservationSource.Kind.PROMETHEUS);
            new CalibrationService(List.of(source), Clock.fixed(NOW))
                    .fetch(configuredWith(PROMETHEUS), catalog(), null);

            assertThat(source.seen.get().operations())
                    .extracting(operation -> operation.operationId().value())
                    .contains("getOrder", "createOrder");
        }
    }

    @Nested
    @DisplayName("testing a source")
    class Verifying {

        @Test
        @DisplayName("tests what was typed, not what was saved")
        void testsTheSourceItIsGiven() {
            // A test button that could only check the previously saved configuration would be
            // useless for the case it exists to serve: somebody filling the form in for the first
            // time.
            var source = new RecordingSource(ObservationSource.Kind.PROMETHEUS);
            var service = new CalibrationService(List.of(source), Clock.fixed(NOW));

            var result = service.verify(PROMETHEUS, null);

            assertThat(result.succeeded()).isTrue();
            assertThat(source.verified.get().end()).isEqualTo(NOW);
        }

        @Test
        void asksOverTheConfiguredWindow() {
            var source = new RecordingSource(ObservationSource.Kind.PROMETHEUS);
            new CalibrationService(List.of(source), Clock.fixed(NOW)).verify(PROMETHEUS, null);

            assertThat(source.verified.get().start()).isEqualTo(NOW.minus(Duration.ofDays(30)));
        }

        @Test
        void needsNoCatalogAtAll() {
            // Unlike a fetch, which refuses without operations to attribute traffic to. A test
            // that demanded an imported API description could not help somebody configuring a
            // brand-new service, which is exactly when they need it.
            var source = new RecordingSource(ObservationSource.Kind.PROMETHEUS);
            var service = new CalibrationService(List.of(source), Clock.fixed(NOW));

            assertThat(service.verify(PROMETHEUS, null).succeeded()).isTrue();
        }

        @Test
        void nothingToTestIsSaidRatherThanThrown() {
            var service = new CalibrationService(List.of(), Clock.fixed(NOW));

            assertThat(service.verify(null, null))
                    .isInstanceOfSatisfying(ProductionObservationSource.NotRetrieved.class,
                            failure -> assertThat(failure.remedy()).isNotBlank());
        }

        @Test
        void aKindWithNoAdapterIsRefusedWithARemedy() {
            var service = new CalibrationService(
                    List.of(new RecordingSource(ObservationSource.Kind.PROMETHEUS)),
                    Clock.fixed(NOW));

            assertThat(service.verify(DYNATRACE, null))
                    .isInstanceOfSatisfying(ProductionObservationSource.NotRetrieved.class,
                            failure -> assertThat(failure.why()).contains("Dynatrace"));
        }
    }

    @Nested
    @DisplayName("refusing, with a remedy")
    class Refusals {

        @Test
        void noConfiguredSourceSaysHowToConfigureOne() {
            var service = new CalibrationService(
                    List.of(new RecordingSource(ObservationSource.Kind.PROMETHEUS)),
                    Clock.fixed(NOW));

            var result = service.fetch(Fixtures.configuration(), catalog(), null);

            assertThat(result).isInstanceOfSatisfying(ProductionObservationSource.NotRetrieved.class,
                    failure -> {
                        assertThat(failure.why()).contains("no observation source is configured");
                        assertThat(failure.remedy()).contains("observation:");
                    });
        }

        @Test
        void noOperationsMeansTheCompositionCouldNotBeAttributedToAnything() {
            // Rates alone could still be fetched, but a volume with no composition is exactly the
            // workload Vortex refuses to guess at. Better to say so than return half an observation.
            var service = new CalibrationService(
                    List.of(new RecordingSource(ObservationSource.Kind.PROMETHEUS)),
                    Clock.fixed(NOW));

            var result = service.fetch(configuredWith(PROMETHEUS), null, null);

            assertThat(result).isInstanceOfSatisfying(ProductionObservationSource.NotRetrieved.class,
                    failure -> {
                        assertThat(failure.why()).contains("no operations have been imported");
                        assertThat(failure.remedy()).contains("Import an API description");
                    });
        }

        @Test
        void everyRefusalAnswersWhatWhyAndWhatNext() {
            var service = new CalibrationService(List.of(), Clock.fixed(NOW));

            var result = service.fetch(Fixtures.configuration(), catalog(), null);

            assertThat(result).isInstanceOfSatisfying(ProductionObservationSource.NotRetrieved.class,
                    failure -> {
                        assertThat(failure.what()).isNotBlank();
                        assertThat(failure.why()).isNotBlank();
                        assertThat(failure.remedy()).isNotBlank();
                        assertThat(failure.describe()).contains(failure.why());
                    });
        }
    }
}
