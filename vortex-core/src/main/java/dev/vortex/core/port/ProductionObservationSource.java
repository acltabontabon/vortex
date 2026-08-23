package dev.vortex.core.port;

import dev.vortex.core.capacity.ObservationSource;
import dev.vortex.core.capacity.ProductionObservation;
import dev.vortex.core.metrics.TimeWindow;
import dev.vortex.core.shared.OperationId;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Retrieves what a service does in production, from the monitoring system that already watches it.
 *
 * <h2>Why this is not {@link ObservabilityProvider}</h2>
 * The two ports look superficially alike — both ask a monitoring system for numbers — and are asking
 * entirely different questions. {@code ObservabilityProvider} watches the service <em>under test</em>
 * while a run is in progress: a window of minutes, sampled live, answering "what happened inside the
 * service as load rose?". This one asks about production over weeks, once, before any test exists,
 * answering "what does this service actually receive?". They differ in window, in cadence, in which
 * deployment they point at and in what a failure means. Folding them into one interface would give
 * every implementation two jobs and every caller a parameter it does not use.
 *
 * <h2>What stays behind this line</h2>
 * PromQL, Dynatrace metric selectors, entity ids and label naming all stop at the adapter. The
 * caller names operations by {@link OperationId} and a path template; the adapter translates that
 * into whatever its system understands and translates the answer back. Nothing downstream can tell
 * which system replied — and no query language reaches a {@code Workload}, which is what lets a
 * calibrated workload read identically to a hand-written one.
 */
public interface ProductionObservationSource {

    /** Short identifier used in provenance and settings, e.g. {@code prometheus}. */
    String id();

    /** Whether this implementation is the one that answers for the configured source. */
    boolean supports(ObservationSource source);

    /**
     * Asks for the observation.
     *
     * <p>Never throws for an expected failure. An unreachable endpoint, a rejected token and a
     * service the system has never heard of are all ordinary outcomes of asking, and each has a
     * different remedy — so they come back as {@link NotRetrieved} carrying that remedy rather than
     * as an exception the caller has to interpret.
     */
    Retrieval retrieve(ObservationRequest request);

    /**
     * Asks the smallest question that proves the configuration works.
     *
     * <p>One query for the peak, and nothing else. That single answer establishes all three things
     * that can be wrong independently: the endpoint is reachable, the credentials are accepted, and
     * the service identifier matches something the system has actually seen. A test that only opened
     * a socket would pass against a Prometheus that has never heard of this service, which is the
     * failure people most often need help with.
     *
     * <p>Returns the same {@link Retrieval} as {@link #retrieve}, carrying a peak-only observation
     * on success — deliberately, so the interface can show a real number rather than a green tick.
     * "Answered: 182 requests/sec at peak" tells the reader whether they pointed it at the right
     * service; "Connected" does not.
     */
    Retrieval verify(ObservationSource source, TimeWindow window, Duration resolution);

    /**
     * What to ask for.
     *
     * @param source     the configured system, including how to reach it
     * @param window     the period to observe, already resolved to absolute instants
     * @param resolution the interval each rate sample should average over
     * @param operations the operations Vortex knows about, so traffic can be attributed to them
     */
    record ObservationRequest(ObservationSource source, TimeWindow window, Duration resolution,
            List<ObservedOperation> operations) {

        public ObservationRequest {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(window, "window");
            Objects.requireNonNull(resolution, "resolution");
            operations = operations == null ? List.of() : List.copyOf(operations);
        }
    }

    /**
     * One operation, described in terms every monitoring system can be asked about.
     *
     * @param operationId  what Vortex calls it
     * @param method       HTTP method, uppercase
     * @param pathTemplate the templated path as the service reports it, e.g. {@code /orders/{id}}
     */
    record ObservedOperation(OperationId operationId, String method, String pathTemplate) {

        public ObservedOperation {
            Objects.requireNonNull(operationId, "operationId");
            method = method == null ? "" : method.trim().toUpperCase(java.util.Locale.ROOT);
            pathTemplate = pathTemplate == null ? "" : pathTemplate.trim();
        }
    }

    /** Either an observation or the reason there isn't one. Never both, never neither. */
    sealed interface Retrieval permits Retrieved, NotRetrieved {

        default boolean succeeded() {
            return this instanceof Retrieved;
        }
    }

    record Retrieved(ProductionObservation observation) implements Retrieval {

        public Retrieved {
            Objects.requireNonNull(observation, "observation");
        }
    }

    /**
     * Why the observation could not be retrieved, in the three parts a person needs.
     *
     * @param what   what Vortex was doing when it failed
     * @param why    what went wrong, in the source system's own terms where that helps
     * @param remedy what the engineer can do about it
     */
    record NotRetrieved(String what, String why, String remedy) implements Retrieval {

        public NotRetrieved {
            what = what == null ? "" : what.trim();
            why = why == null ? "" : why.trim();
            remedy = remedy == null ? "" : remedy.trim();
            if (what.isBlank() || why.isBlank()) {
                throw new IllegalArgumentException(
                        "a failed retrieval must say what failed and why; 'it did not work' is not "
                                + "an error message");
            }
        }

        /** The whole message, for a terminal or a flash notice. */
        public String describe() {
            return remedy.isBlank() ? what + ": " + why : what + ": " + why + " " + remedy;
        }
    }
}
