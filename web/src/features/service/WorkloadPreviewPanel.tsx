import { Text } from '@mantine/core';
import type { MixRow } from '../../api/workspace';
import type { ShapeDto } from '../../api/tests';
import { TrafficDistribution } from '../../components/TrafficDistribution';
import { LoadShapeChart } from '../../components/charts/LoadShapeChart';
import classes from './WorkloadPreviewPanel.module.css';

/**
 * Everything {@link WorkloadPreviewPanel} needs, published by the composer as the form changes.
 * `headline` is the one piece of English in here, and it comes straight from `/tests/preview` — no
 * "ramp vs. hold" branch lives in this file, because once Spike and safety-ceiling-aware Breakpoint
 * framing exist, a client-side branch on `ramping` describes both of those wrong. Everything else
 * (`shape`, `composition`) is the same domain-computed data the Operations region's mixer already
 * reads.
 */
export interface ComposerPreviewSnapshot {
  testTypeLabel: string;
  /** The workload in plain English, e.g. "10 req/s for 1 min" or "Jump from 10 req/s to 100 req/s
   *  and back over 2m" — built once, in the domain, by `WorkloadRecommendation.headlineFor`, and
   *  reused verbatim here so the recommendation card and this rail never disagree. */
  headline: string | null;
  durationMinutes: number;
  composition: MixRow[] | null;
  shape: ShapeDto | null;
  problem: string | null;
  /** The test's target, e.g. "Docker: payment-service:1.4.2" — null for an external endpoint (its
   *  address is already stated on the service header, on every tab) or when no environment is
   *  configured yet. */
  targetSummary: string | null;
  /** The target's declared resource envelope, e.g. "0.5 CPU · 512 MiB" — null whenever no envelope
   *  applies (an external endpoint) or is known. */
  resourceSummary: string | null;
}

function subline(snapshot: ComposerPreviewSnapshot): string {
  const duration = `${snapshot.durationMinutes} min`;
  const stageCount = snapshot.shape?.stages.length ?? 0;
  return stageCount > 1 ? `${duration} · ${stageCount} stages` : duration;
}

/** A caption, not a paragraph — narrates `composition`, already domain-computed, rather than
 *  restating the headline's own shape/level clause. */
function sentence(snapshot: ComposerPreviewSnapshot): string | null {
  if (!snapshot.composition || snapshot.composition.length === 0) return null;
  const top = [...snapshot.composition].sort((a, b) => b.shareFraction - a.shareFraction)[0];
  const traffic = top.path || top.label;
  return `Distributing most traffic to ${traffic}.`;
}

/**
 * The composer's own right rail — what Vortex is about to do, at a glance. Replaces Recent Runs in
 * the same grid slot while composing (see `OverviewPage.tsx`): activity is what matters while
 * browsing tests, this preview is what matters while shaping one, so the rail's job changes rather
 * than a second column appearing beside it.
 *
 * <p>Deliberately not built from `Fact`/`Facts` — this panel's hierarchy (a headline number in the
 * chosen model's own language, then the traffic mix, then a quiet caption) reads better as its own
 * composition than as a grid of individually-labelled facts. `Fact` stays reserved for where a
 * single labelled value is genuinely the strongest shape, which this isn't.
 */
export function WorkloadPreviewPanel({
  snapshot,
  showChart,
}: {
  snapshot: ComposerPreviewSnapshot | null;
  /** False on narrow screens, where the composer's Load region already shows this same chart
   *  inline (there's no rail slot to borrow there — this panel still renders, stacked below Tests,
   *  but must not show the identical chart a second time). */
  showChart: boolean;
}) {
  return (
    <section className={classes.panel}>
      <div className={classes.eyebrow}>Workload</div>

      {!snapshot ? (
        <Text size="sm" c="dimmed">
          Fill in Intent and Load to see the shape.
        </Text>
      ) : (
        <>
          <div className={classes.type}>{snapshot.testTypeLabel}</div>
          <div className={classes.headline}>{snapshot.headline ?? '—'}</div>
          <div className={classes.subline}>{subline(snapshot)}</div>

          {showChart && snapshot.shape && (
            <div className={classes.chart}>
              <LoadShapeChart shape={snapshot.shape} />
            </div>
          )}

          <div className={classes.eyebrow} data-mt>
            Traffic
          </div>
          {snapshot.problem ? (
            <Text size="sm" c="dimmed">
              {snapshot.problem}
            </Text>
          ) : snapshot.composition && snapshot.composition.length > 0 ? (
            <TrafficDistribution
              rows={snapshot.composition}
              concurrency={snapshot.shape?.unit === 'VUs'}
            />
          ) : (
            <Text size="sm" c="dimmed">
              Give at least one operation a share of the traffic.
            </Text>
          )}

          {sentence(snapshot) && <p className={classes.caption}>{sentence(snapshot)}</p>}

          {(snapshot.targetSummary || snapshot.resourceSummary) && (
            <p className={classes.caption}>
              {snapshot.targetSummary && <>Target · {snapshot.targetSummary}</>}
              {snapshot.targetSummary && snapshot.resourceSummary && ' · '}
              {snapshot.resourceSummary && <>Resources · {snapshot.resourceSummary}</>}
            </p>
          )}
        </>
      )}
    </section>
  );
}
