/**
 * How much weight each section of a run's evidence deserves, by test type.
 *
 * <p>A smoke test's whole point is "did the workload execute and were objectives met" — capacity is
 * structurally never established for one (see {@code SustainabilityCondition}'s "held for the
 * sustain duration" condition, which a smoke test's plan never defines a duration for), so leading
 * with a capacity claim would draw attention to a section with nothing to say. A breakpoint run's
 * whole point is the opposite: the level progression and the first failure are the answer.
 *
 * <p>This is presentation weighting only — which section leads, which narrative angle a run's own
 * findings get read through — never a second copy of a domain rule. Whether capacity can be
 * established at all is still decided once, in `SustainableCapacityCalculator`; this only decides
 * how much room the page gives the answer.
 */

export interface TestTypeEmphasis {
  /** Whether a level-by-level PASS/FAIL progression is worth drawing at all — false for single-stage
   *  or non-ramping test types, where {@code CapacityCurve} would have at most one point to show. */
  showCapacityCurve: boolean;
  /** Which of the "narrative focus" angles best introduces this test type's own findings — used to
   *  order {@code narrative.ts}'s output, not to filter it. */
  narrativeFocus: 'connectivity' | 'objectives' | 'levelProgression' | 'reaction' | 'trend';
  /** Key-metrics tile order — the tile most worth a glance first, for this test type. */
  keyMetricsPriority: Array<'load' | 'latency' | 'errors' | 'resources' | 'capacity'>;
}

export const DEFAULT_EMPHASIS: TestTypeEmphasis = {
  showCapacityCurve: false,
  narrativeFocus: 'objectives',
  keyMetricsPriority: ['load', 'latency', 'errors', 'resources', 'capacity'],
};

/** Keyed on the raw `TestType` enum name — `identity.testType` — never on `testTypeLabel`, which is
 *  free to reword. Falls back to {@link DEFAULT_EMPHASIS} for a run recorded before this field
 *  existed, or a type this page doesn't yet have a specific opinion about. */
export const TEST_TYPE_EMPHASIS: Record<string, TestTypeEmphasis> = {
  SMOKE: {
    showCapacityCurve: false,
    narrativeFocus: 'connectivity',
    keyMetricsPriority: ['load', 'errors', 'latency', 'resources', 'capacity'],
  },
  AVERAGE_LOAD: {
    showCapacityCurve: false,
    narrativeFocus: 'objectives',
    keyMetricsPriority: ['load', 'latency', 'errors', 'resources', 'capacity'],
  },
  STRESS: {
    showCapacityCurve: true,
    narrativeFocus: 'levelProgression',
    keyMetricsPriority: ['capacity', 'latency', 'errors', 'resources', 'load'],
  },
  SPIKE: {
    showCapacityCurve: false,
    narrativeFocus: 'reaction',
    keyMetricsPriority: ['latency', 'errors', 'load', 'resources', 'capacity'],
  },
  SOAK: {
    showCapacityCurve: false,
    narrativeFocus: 'trend',
    keyMetricsPriority: ['errors', 'resources', 'latency', 'load', 'capacity'],
  },
  BREAKPOINT: {
    showCapacityCurve: true,
    narrativeFocus: 'levelProgression',
    keyMetricsPriority: ['capacity', 'latency', 'errors', 'resources', 'load'],
  },
};

export function emphasisFor(testType: string | null): TestTypeEmphasis {
  if (!testType) return DEFAULT_EMPHASIS;
  return TEST_TYPE_EMPHASIS[testType] ?? DEFAULT_EMPHASIS;
}
