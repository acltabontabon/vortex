import { Text } from '@mantine/core';
import type { MixRow } from '../../api/workspace';
import type { ShapeDto } from '../../api/tests';
import { TrafficDistribution } from '../../components/TrafficDistribution';
import { LoadShapeChart } from '../../components/charts/LoadShapeChart';
import classes from './WorkloadPreviewPanel.module.css';

/**
 * Everything {@link WorkloadPreviewPanel} needs, published by the composer as the form changes —
 * raw values already known client-side (what the user typed) plus the one thing the domain
 * computed (`composition`, from the same `/tests/preview` call the Operations region's mixer
 * already uses). Nothing here is arithmetic this file performs itself.
 */
export interface ComposerPreviewSnapshot {
  testTypeLabel: string;
  model: 'OPEN' | 'CLOSED';
  ramping: boolean;
  rate: number;
  vus: number;
  durationMinutes: number;
  peakRate: number | '';
  stages: number;
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

function headline(snapshot: ComposerPreviewSnapshot): string {
  if (snapshot.model === 'CLOSED') {
    return `Drive ${snapshot.vus} concurrent users`;
  }
  if (snapshot.ramping && snapshot.peakRate !== '') {
    return `Ramp to ${snapshot.peakRate} req/s`;
  }
  return `Hold ${snapshot.rate} req/s`;
}

function subline(snapshot: ComposerPreviewSnapshot): string {
  const duration = `${snapshot.durationMinutes} min`;
  return snapshot.ramping ? `${duration} · ${snapshot.stages} stages` : duration;
}

/** A caption, not a paragraph — deterministic narration of numbers already on screen above it, so
 *  it reads as the instrument's own caption rather than a second, competing explanation. */
function sentence(serviceName: string, snapshot: ComposerPreviewSnapshot): string | null {
  if (!snapshot.composition || snapshot.composition.length === 0) return null;
  const top = [...snapshot.composition].sort((a, b) => b.shareFraction - a.shareFraction)[0];
  const verb = snapshot.model === 'CLOSED' ? 'Drive' : snapshot.ramping ? 'Ramp' : 'Hold';
  const level =
    snapshot.model === 'CLOSED'
      ? `${snapshot.vus} concurrent users`
      : snapshot.ramping && snapshot.peakRate !== ''
        ? `to ${snapshot.peakRate} req/s`
        : `${snapshot.rate} req/s`;
  const shape = snapshot.ramping ? ` across ${snapshot.stages} stages` : '';
  const traffic = top.path || top.label;
  return `${verb} ${serviceName} ${level}${shape} for ${snapshot.durationMinutes} min, distributing most traffic to ${traffic}.`;
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
  serviceName,
  snapshot,
  showChart,
}: {
  serviceName: string;
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
          <div className={classes.headline}>{headline(snapshot)}</div>
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
            <TrafficDistribution rows={snapshot.composition} concurrency={snapshot.model === 'CLOSED'} />
          ) : (
            <Text size="sm" c="dimmed">
              Give at least one operation a share of the traffic.
            </Text>
          )}

          {sentence(serviceName, snapshot) && (
            <p className={classes.caption}>{sentence(serviceName, snapshot)}</p>
          )}

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
