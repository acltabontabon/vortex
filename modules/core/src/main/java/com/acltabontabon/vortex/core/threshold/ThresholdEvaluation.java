package com.acltabontabon.vortex.core.threshold;

import java.util.List;

/**
 * The complete deterministic verdict for one execution.
 *
 * <p>The overall outcome is deliberately strict: any failure is a failure, and a threshold that
 * could not be evaluated prevents an unqualified pass. Reporting "passed" when an objective was
 * never actually checked is how a performance test quietly stops being evidence.
 */
public record ThresholdEvaluation(List<ThresholdResult> results) {

    public ThresholdEvaluation {
        results = results == null ? List.of() : List.copyOf(results);
    }

    public static ThresholdEvaluation empty() {
        return new ThresholdEvaluation(List.of());
    }

    public Verdict overall() {
        if (results.isEmpty()) {
            return Verdict.NOT_EVALUATED;
        }
        if (results.stream().anyMatch(r -> r.verdict() == Verdict.FAIL)) {
            return Verdict.FAIL;
        }
        if (results.stream().anyMatch(r -> r.verdict() == Verdict.NOT_EVALUATED)) {
            return Verdict.NOT_EVALUATED;
        }
        return Verdict.PASS;
    }

    public boolean passed() {
        return overall() == Verdict.PASS;
    }

    public boolean violated() {
        return overall() == Verdict.FAIL;
    }

    public List<ThresholdResult> failures() {
        return results.stream().filter(r -> r.verdict() == Verdict.FAIL).toList();
    }

    public List<ThresholdResult> unevaluated() {
        return results.stream().filter(r -> r.verdict() == Verdict.NOT_EVALUATED).toList();
    }
}
