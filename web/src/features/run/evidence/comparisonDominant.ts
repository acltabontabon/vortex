import type { ComparisonEvidence, MetricDelta } from '../../../api/run';

/**
 * Which delta(s) the comparison's own verdict is actually about.
 *
 * <p>`RegressionEvaluator` classifies the whole comparison — `REGRESSED`, `IMPROVED`, `UNCHANGED` —
 * without ranking which metric caused it; a reader is left to scan every row. This is a
 * presentation-layer *selection* over `MetricDelta.isDegradation`, a value the domain already
 * computed at its own noise threshold: it filters and sorts, and asserts nothing the domain did not
 * already assert. It never re-derives degradation itself (no re-comparing baseline/candidate, no new
 * threshold).
 */

export function dominantDeltas(comparison: ComparisonEvidence): MetricDelta[] {
  return comparison.deltas
    .filter((delta) => delta.isDegradation === true)
    .sort((a, b) => Math.abs(b.percentChange ?? 0) - Math.abs(a.percentChange ?? 0));
}

export function improvedDeltas(comparison: ComparisonEvidence): MetricDelta[] {
  return comparison.deltas
    .filter((delta) => delta.isDegradation === false)
    .sort((a, b) => Math.abs(b.percentChange ?? 0) - Math.abs(a.percentChange ?? 0));
}

/** Everything else — too small to classify, or no percentage applies. Shown only on request. */
export function unclassifiedDeltas(comparison: ComparisonEvidence): MetricDelta[] {
  return comparison.deltas.filter((delta) => delta.isDegradation === null);
}
