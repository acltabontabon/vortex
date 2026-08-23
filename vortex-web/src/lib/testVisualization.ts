/**
 * What a test's own question calls for on screen, decided once and named — never re-derived at
 * every call site as a scatter of `if (testType === X)` checks.
 *
 * <p>The rule this file enforces: a test's primary visualization must visually answer the primary
 * engineering question that test type exists to answer (see each `TestType`'s own `question()` in
 * {@code com.acltabontabon.vortex.core.workload.TestType}). Concretely:
 *
 * <ul>
 *   <li><strong>Smoke</strong> — "is the workload valid and the service reachable?" has no position
 *       on a magnitude scale, so it gets the same lightweight time-series view as Soak.
 *   <li><strong>Average load</strong> — "does it meet objectives under normal traffic?" is a
 *       comparison against one known reference, not a boundary hunt — a compact summary, not a
 *       scale.
 *   <li><strong>Stress</strong> — "how does it behave at or above peak?" is a magnitude/boundary
 *       question, same shape as Breakpoint, but the story is pressure progressing through stages
 *       rather than the boundary itself, so it gets the same wide range figure plus the stage
 *       ladder Breakpoint omits.
 *   <li><strong>Spike</strong> — "how does it react to a sudden jump?" is fundamentally temporal
 *       (baseline → jump → behavior during → recovery), not a magnitude comparison — it shares
 *       Soak/Smoke's time-series primitive, annotated at the jump rather than at a compliance
 *       breakpoint, with any magnitude reference folded in as a caption rather than a chart of its
 *       own.
 *   <li><strong>Soak</strong> — "does performance degrade over sustained load?" is squarely
 *       temporal — time-series, annotated at the first compliance breakpoint if one exists.
 *   <li><strong>Breakpoint</strong> — "where does it stop meeting objectives?" is the canonical
 *       boundary hunt — the wide range figure, with its first-failing mark emphasized rather than
 *       diluted by a stage-by-stage ladder.
 * </ul>
 *
 * <p>Three reusable primitives serve all six kinds — {@code TimeSeriesFigure} (Smoke, Soak, Spike),
 * {@code CapacityRangeFigure} (Stress, Breakpoint, wide), and {@code EvidenceScale} (the fallback
 * "range-compact" shape any kind can degrade to). No kind gets a bespoke component; only the
 * annotation, emphasis and secondary reference passed to a primitive vary. `LoadSummary` (Average
 * load) is the one genuinely different shape, because its question is a different shape of
 * question — a comparison, not a magnitude-over-time or magnitude-on-a-line story — not because
 * `AVERAGE_LOAD` is a different enum value.
 *
 * <p>This function decides the *shape*; it never decides *whether there is data* beyond what's
 * passed in (`hasTimeline`/`hasRange`), and it computes no new facts — every fact it can point a
 * renderer at was already computed in vortex-core.
 */

import type { CapacityRange } from '../api/workspace';

export type VisualizationPlan =
  | { primitive: 'time-series'; annotation: 'breakpoint' | 'jump' }
  | { primitive: 'range-wide'; emphasis: 'breakpoint' | 'pressure'; showStageLadder: boolean }
  | { primitive: 'range-compact' }
  | { primitive: 'load-summary' }
  | { primitive: 'unavailable' };

type PrimaryShape = 'time-series' | 'range-wide' | 'load-summary';

/** The one place a `TestType` maps to a shape of question. Everything else in this file is just
 *  "what does that shape need to fall back to when its preferred data isn't there yet." */
const PRIMARY_SHAPE: Record<string, PrimaryShape> = {
  SMOKE: 'time-series',
  SOAK: 'time-series',
  SPIKE: 'time-series',
  AVERAGE_LOAD: 'load-summary',
  STRESS: 'range-wide',
  BREAKPOINT: 'range-wide',
};

export function chooseVisualization(args: {
  testType: string;
  hasTimeline: boolean;
  hasRange: boolean;
}): VisualizationPlan {
  const { testType, hasTimeline, hasRange } = args;
  const shape = PRIMARY_SHAPE[testType];

  switch (shape) {
    case 'time-series':
      if (hasTimeline) {
        return { primitive: 'time-series', annotation: testType === 'SPIKE' ? 'jump' : 'breakpoint' };
      }
      // A run too short to leave a timeline (a genuinely minimal Smoke check, say) still has
      // whatever the range can show — the same graceful step every other kind takes below.
      return hasRange ? { primitive: 'range-compact' } : { primitive: 'unavailable' };

    case 'range-wide':
      return hasRange
        ? {
            primitive: 'range-wide',
            emphasis: testType === 'BREAKPOINT' ? 'breakpoint' : 'pressure',
            showStageLadder: testType === 'STRESS',
          }
        : { primitive: 'unavailable' };

    case 'load-summary':
      return hasRange ? { primitive: 'load-summary' } : { primitive: 'unavailable' };

    default:
      // An unrecognised or future TestType carries no assumptions — show whatever the range can,
      // the same fallback every kind above reaches for once its own preferred data is absent.
      return hasRange ? { primitive: 'range-compact' } : { primitive: 'unavailable' };
  }
}

/**
 * A magnitude fact worth stating once, in words, without turning a temporal chart back into a
 * scale — Spike's own "a capacity reference can be secondary, but should not erase the temporal
 * nature of the test." Built entirely from marks the domain already computed and labelled; nothing
 * here is measured or estimated by this file.
 */
export function rangeReferenceCaption(range: CapacityRange): string | null {
  if (!range.renderable) return null;
  const peak = range.markers.find((marker) => marker.kind === 'FIRST_FAILING')
    ?? range.markers.find((marker) => marker.kind === 'TESTED_CAPACITY');
  if (!peak) return null;

  const production = range.markers.find((marker) => marker.kind === 'PRODUCTION');
  const verb = peak.kind === 'FIRST_FAILING' ? 'Reached' : 'Peaked at';
  return production
    ? `${verb} ${peak.displayWithUnit}, against a production peak of ${production.displayWithUnit}`
    : `${verb} ${peak.displayWithUnit}`;
}
