package dev.vortex.core.evidence;

import java.util.List;
import java.util.Objects;

/**
 * A statement Vortex can make from the measurements alone.
 *
 * <p>This is the third tier of the evidence model — pure functions over normalised measurements,
 * the same input giving the same output every time. It is deliberately not the same thing as
 * {@code analysis.Finding}, which is the fourth tier: an interpretation produced by a language
 * model. The two are never merged and never rendered as though they were the same kind of claim.
 *
 * <h2>Language</h2>
 *
 * <p>A finding says <em>coincided with</em> or <em>correlated strongly with</em>. It never says
 * <em>caused by</em>. A load test observes association between two signals moving together; the
 * step from association to cause takes context a run does not contain, and a sentence that skips it
 * will eventually be quoted in a review as though it had not. The say/not-say table in
 * {@code docs/02-architecture/execution-and-evidence.adoc} (Evidence model) is binding here and is
 * enforced by test.
 *
 * <h2>Citations</h2>
 *
 * <p>Every finding cites at least one evidence identifier, checked in the constructor. A finding
 * with nothing behind it is an opinion, and the whole reason this tier exists is that its statements
 * can be traced to measurements that were actually taken.
 *
 * @param id         stable reference, e.g. {@code finding:throughput.shortfall}
 * @param level      how much attention it deserves
 * @param headline   one line, readable on its own
 * @param detail     the supporting sentences; may be empty when the headline says everything
 * @param strength   how firmly the evidence supports it
 * @param evidenceIds identifiers from {@code EvidenceIds}, never empty
 */
public record DeterministicFinding(
        String id,
        FindingLevel level,
        String headline,
        String detail,
        dev.vortex.core.analysis.EvidenceStrength strength,
        List<String> evidenceIds) {

    public static final int MAX_HEADLINE_LENGTH = 160;

    /** Matches {@code analysis.Finding.MAX_STATEMENT_LENGTH}, so both tiers truncate alike. */
    public static final int MAX_DETAIL_LENGTH = 600;

    public DeterministicFinding {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(strength, "strength");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a finding must have a stable identifier");
        }
        if (headline == null || headline.isBlank()) {
            throw new IllegalArgumentException("a finding must state something");
        }
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "the finding '" + headline + "' cites no evidence. A deterministic finding is "
                            + "derived from measurements, so it can always name at least one; a "
                            + "finding that cannot is an opinion and must not be presented as a "
                            + "result.");
        }
        headline = truncate(headline.trim(), MAX_HEADLINE_LENGTH);
        detail = detail == null ? "" : truncate(detail.trim(), MAX_DETAIL_LENGTH);
        evidenceIds = List.copyOf(evidenceIds);
    }

    private static String truncate(String text, int maximum) {
        return text.length() <= maximum ? text : text.substring(0, maximum);
    }

    public boolean hasDetail() {
        return !detail.isEmpty();
    }
}
