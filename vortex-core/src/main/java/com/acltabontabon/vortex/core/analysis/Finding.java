package com.acltabontabon.vortex.core.analysis;

import java.util.List;
import java.util.Objects;

/**
 * One interpretation of the measurements, with the evidence it rests on.
 *
 * <p>Every finding must cite evidence by identifier, and Vortex resolves those identifiers against
 * the measurements that were actually collected. A finding whose evidence cannot be resolved is
 * discarded rather than shown, and the gap is reported as missing telemetry instead. This is what
 * stops "CPU remained below 58%" from appearing in a report where CPU was never measured.
 *
 * <p>Language matters here too. A finding says <em>correlated with</em> or
 * <em>strongest hypothesis</em>, not <em>caused by</em> — a load test observes association, and
 * the step from association to cause is one a human engineer takes with more context than a test
 * run contains. {@code type} makes that distinction structural rather than a matter of phrasing:
 * an {@link FindingType#OBSERVATION} and a {@link FindingType#HYPOTHESIS} are never allowed to look
 * the same once {@code confidence} is taken into account — see {@link FindingType#maxConfidence()}.
 *
 * @param statement  the interpretation, in plain language
 * @param type       what kind of claim this is — observed, correlated, hypothesised, or a stated
 *                   limitation
 * @param confidence how strongly it is asserted
 * @param evidenceIds identifiers of the measurements supporting it
 */
public record Finding(String statement, FindingType type, Confidence confidence,
        List<String> evidenceIds) {

    public static final int MAX_STATEMENT_LENGTH = 600;

    public Finding {
        Objects.requireNonNull(confidence, "confidence");
        // Old persisted analyses predate this field; the most conservative type is the honest
        // default for a claim whose original kind was never recorded.
        type = type == null ? FindingType.HYPOTHESIS : type;
        if (statement == null || statement.isBlank()) {
            throw new IllegalArgumentException("a finding must state something");
        }
        statement = statement.trim();
        if (statement.length() > MAX_STATEMENT_LENGTH) {
            statement = statement.substring(0, MAX_STATEMENT_LENGTH);
        }
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }

    /** Convenience constructor for callers that have not classified the finding's type. */
    public Finding(String statement, Confidence confidence, List<String> evidenceIds) {
        this(statement, FindingType.HYPOTHESIS, confidence, evidenceIds);
    }

    public boolean isSupported() {
        return !evidenceIds.isEmpty();
    }
}
