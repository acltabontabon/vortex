package dev.vortex.core.evidence;

import dev.vortex.core.capacity.HeadroomCalculator;
import dev.vortex.core.capacity.ProductionObservation;
import java.util.Objects;
import java.util.Optional;

/**
 * The project-level facts a report needs that a single execution does not contain.
 *
 * <h2>Why this exists rather than another parameter</h2>
 * Headroom used to be one. It was declared on {@code assemble(...)}, rendered by every template and
 * every exporter, and passed {@code null} at all four call sites — so a figure that had been correct
 * for months appeared nowhere. The fix is not to fill the parameter in four places; it is to make it
 * impossible to omit. A fifth call site would have repeated the mistake.
 *
 * <p>Resolved <em>before</em> assembly, by something that can reach a repository, so
 * {@link dev.vortex.core.application.RunEvidenceService} stays a deterministic transformation from
 * inputs to evidence rather than a service that queries the database halfway through building a
 * report.
 *
 * @param headroom either a value or a stated reason there is not one — never null, never silence
 * @param production the observed production traffic this run is being read against, when the project
 *                   has recorded any. Carried so a report citing headroom can also say how much the
 *                   baseline behind it is worth
 */
public record EvidenceContext(HeadroomCalculator.Result headroom,
        ProductionObservation production) {

    public EvidenceContext {
        Objects.requireNonNull(headroom,
                "headroom must be resolved before assembly: either a value or the reason there "
                        + "isn't one. Silence is what this type exists to prevent.");
    }

    /**
     * A context for a project with no production observation and no capacity history.
     *
     * <p>Still carries a reason rather than nothing, so a report says why it cannot state headroom.
     */
    public static EvidenceContext none() {
        return new EvidenceContext(new HeadroomCalculator().calculateFromTestedCapacity(null, false, null), null);
    }

    public Optional<ProductionObservation> productionIfPresent() {
        return Optional.ofNullable(production);
    }
}
