package dev.vortex.core.calibration;

import dev.vortex.core.capacity.ProductionObservation;
import dev.vortex.core.project.ProjectConfiguration;
import dev.vortex.core.shared.LoadLevel;
import dev.vortex.core.shared.RequestsPerSecond;
import dev.vortex.core.workload.Observation;
import dev.vortex.core.workload.Workload;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether the production traffic a workload was derived from is still what production does.
 *
 * <p>A workload built from an observation carries an assumption: that the numbers it holds describe
 * what the service actually receives. That assumption expires quietly. Traffic grows, a campaign
 * lands, a caller is retired — and a test still passes against a load nobody sees any more, which is
 * the most expensive kind of green.
 *
 * <p>Everything needed to notice was already recorded and never compared. Each workload's
 * {@link dev.vortex.core.workload.WorkloadSource} keeps the observation window it was derived from,
 * and {@link Observation#anchor()} has carried a javadoc since it was written saying it exists "for
 * ordering and staleness checks". This is that check.
 *
 * <h2>The rule, and the absence of a threshold</h2>
 *
 * <p>There is no tolerance constant here and there should not be one. Choosing that five per cent is
 * fine and six is not would be Vortex inventing a judgement nobody asked it for. The rule is exactly:
 * <em>from the traffic recorded now, Vortex would propose a different number than this workload
 * holds</em> — measured through {@link CalibrationPolicy}'s own rounding, so the comparison and the
 * proposal cannot disagree about what "different" means. That is also why this class lives in this
 * package: {@code round} is package-private, and a second rounding rule would eventually report
 * drift that accepting the suggestion would not fix.
 *
 * <h2>Three answers, and why not two</h2>
 *
 * <p>{@link Unchanged} and {@link Drifted} are the interesting ones, but most real configurations
 * contain workloads this question does not apply to: hand-written ones with no production assumption
 * to drift from, ones recording no observation window, ones measured in virtual users. Every one of
 * those is {@link NotAssessable} with a reason, and never a drift verdict.
 *
 * <p>That asymmetry is the point. A false "your workload is out of date" costs somebody an
 * afternoon proving it is not, and it costs this check its credibility permanently. Silence is the
 * safe failure and it is always the one taken.
 */
public final class WorkloadDrift {

    private final CalibrationPolicy policy;

    public WorkloadDrift(CalibrationPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** What this check concluded about one workload. Always renderable, whichever way it went. */
    public sealed interface Assessment permits Unchanged, Drifted, NotAssessable {

        Workload workload();

        /** One sentence stating the finding, or stating why there is not one. */
        String statement();
    }

    /** Production still does what this workload assumes. */
    public record Unchanged(Workload workload, LoadLevel current, String statement)
            implements Assessment {
    }

    /**
     * Production has moved away from what this workload assumes.
     *
     * @param derivedFrom  the level the workload holds
     * @param proposedNow  what Vortex would propose from the traffic recorded now
     * @param derivation   the proposal's own arithmetic, so the claim is checkable rather than
     *                     merely asserted
     */
    public record Drifted(Workload workload, LoadLevel derivedFrom, LoadLevel proposedNow,
            Observation baselineWindow, Observation currentWindow, String derivation,
            String statement) implements Assessment {
    }

    /** The question does not apply, or cannot be answered. Never a drift verdict. */
    public record NotAssessable(Workload workload, String reason) implements Assessment {

        @Override
        public String statement() {
            return reason;
        }
    }

    /** Every workload in a configuration, against the production traffic recorded on it. */
    public List<Assessment> assess(ProjectConfiguration configuration) {
        if (configuration == null) {
            return List.of();
        }
        ProductionObservation current =
                configuration.productionObservationIfPresent().orElse(null);
        return configuration.workloads().stream()
                .map(workload -> assess(workload, current))
                .toList();
    }

    /** Only the ones that actually moved — what an attention list wants. */
    public List<Drifted> drifted(ProjectConfiguration configuration) {
        return assess(configuration).stream()
                .filter(Drifted.class::isInstance)
                .map(Drifted.class::cast)
                .toList();
    }

    /**
     * Whether this workload's production assumption still holds.
     *
     * @param current what production is recorded as doing now; may be null
     */
    public Assessment assess(Workload workload, ProductionObservation current) {
        Objects.requireNonNull(workload, "workload");

        if (current == null) {
            return notAssessable(workload,
                    "No production traffic is recorded for this service, so there is nothing to "
                            + "compare this workload against.");
        }

        // A hand-entered number has no production assumption, so it cannot have drifted away from
        // one. Saying so is different from saying it is fine.
        if (!workload.source().kind().isProductionInformed()) {
            return notAssessable(workload, workload.source().kind().label()
                    + ": this workload states its own figures rather than deriving them from "
                    + "production, so nothing here can go out of date.");
        }

        // The guard that keeps this check trustworthy. A workload edited by hand in YAML may carry
        // no window at all, and "we do not know when this came from" must never become "this is out
        // of date" — the two are not close to the same claim.
        if (workload.source().observation().anchor().isEmpty()) {
            return notAssessable(workload,
                    "This workload records no observation window, so Vortex cannot tell which "
                            + "production traffic it was derived from.");
        }
        if (current.observation().anchor().isEmpty()) {
            return notAssessable(workload,
                    "The production traffic on file records no observation window, so Vortex "
                            + "cannot tell whether it is newer than this workload.");
        }

        Optional<WorkloadSuggestion> proposal = proposalFor(workload, current);
        if (proposal.isEmpty()) {
            return notAssessable(workload,
                    "Vortex proposes no workload of this kind from an observation, so there is no "
                            + "current figure to compare this one with.");
        }

        RequestsPerSecond proposed = proposal.get().rate();
        LoadLevel held = workload.peakLevel();

        // The same refusal HeadroomCalculator makes. A workload measured in virtual users cannot
        // drift against an arrival rate: they are different quantities, and comparing them would be
        // a conversion that does not exist.
        if (proposed == null || held == null || !proposed.sameQuantityAs(held)) {
            return notAssessable(workload,
                    "This workload is stated in " + (held == null ? "no level" : held.unit())
                            + ", and production traffic is recorded in requests/sec. The two are "
                            + "different quantities and cannot be compared.");
        }

        RequestsPerSecond heldRounded = policy.round(BigDecimal.valueOf(held.asDouble()));
        if (proposed.value().compareTo(heldRounded.value()) == 0) {
            return new Unchanged(workload, held,
                    "From the production traffic recorded now, Vortex would still propose "
                            + proposed.displayWithUnit() + " for this workload.");
        }

        return new Drifted(workload, held, proposed,
                workload.source().observation(), current.observation(),
                proposal.get().derivation(),
                "This workload applies " + held.displayWithUnit()
                        + ", derived from production traffic observed "
                        + workload.source().observation().describe()
                        + ". From the traffic recorded now, Vortex would propose "
                        + proposed.displayWithUnit() + ".");
    }

    /**
     * The current proposal for this workload, if the policy makes one of its kind.
     *
     * <p>Matched by name first so a renamed-but-still-derived workload is compared against the
     * suggestion it actually came from, then by test type. Several types — smoke, spike, soak — are
     * not proposed from an observation at all, and those simply have no counterpart.
     */
    private Optional<WorkloadSuggestion> proposalFor(Workload workload,
            ProductionObservation current) {

        List<WorkloadSuggestion> suggestions = policy.propose(current);
        return suggestions.stream()
                .filter(suggestion -> suggestion.name().equals(workload.name()))
                .findFirst()
                .or(() -> suggestions.stream()
                        .filter(suggestion -> suggestion.type() == workload.type())
                        .findFirst());
    }

    private NotAssessable notAssessable(Workload workload, String reason) {
        return new NotAssessable(workload, reason);
    }
}
