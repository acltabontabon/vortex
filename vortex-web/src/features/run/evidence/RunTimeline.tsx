import { Stack, Text, Title } from '@mantine/core';
import type { ResourceKindPlot, ResourceTimelineEvidence, TimelineEvidence } from '../../../api/run';
import { TimelineChart } from '../../../components/charts/TimelineChart';
import { ResourceKindChart } from '../../../components/charts/ResourceKindChart';
import { type ChartMarker, toEpochSeconds } from '../../../components/charts/chartTime';
import classes from './RunTimeline.module.css';

/**
 * Workload, latency, CPU and memory, stacked as one instrument sharing a single time axis and a
 * single hover cursor — so "throughput flattened while p95 rose while CPU approached its limit" is
 * something a reader sees by dragging across any one track, rather than something they have to
 * reconstruct from four independent charts.
 *
 * <p>All tracks share one Recharts `syncId` (via each chart's own `lineChartProps` escape hatch) and
 * one time origin, computed once here from whichever plot has the earliest point — the same
 * "one origin, so a marker lines up everywhere" mechanism {@code TimelineChart} already used for a
 * single figure, generalized across every track in this one.
 */
export function RunTimeline({
  executionId,
  timeline,
  resourceTimeline,
}: {
  executionId: string;
  timeline: TimelineEvidence;
  resourceTimeline: ResourceTimelineEvidence;
}) {
  const throughputPlot = timeline.plots.find((p) => p.hasData && /throughput/i.test(p.label));
  const latencyPlot = timeline.plots.find((p) => p.hasData && /latency/i.test(p.label));
  const cpuPlot = resourceTimeline.plots.find((p) => p.kind === 'CPU');
  const memoryPlot = resourceTimeline.plots.find((p) => p.kind === 'MEMORY');

  // Memory, unlike CPU, is never drawn as one overlaid chart: a container's own limit and the load
  // generator's machine are different quantities by orders of magnitude (hundreds of MiB versus tens
  // of GiB), so one shared axis would flatten the container's own line to invisible. Split by scope,
  // each on the axis its own numbers actually need — the same "never let generator limits look like
  // service limits" rule that already keeps their tables apart.
  const memorySutPlot = memoryPlot ? scopedPlot(memoryPlot, 'SYSTEM_UNDER_TEST') : null;
  const memoryGeneratorPlot = memoryPlot ? scopedPlot(memoryPlot, 'LOAD_GENERATOR') : null;

  const hasAnyTrack = Boolean(
    throughputPlot || latencyPlot || cpuPlot || memorySutPlot || memoryGeneratorPlot,
  );
  if (!hasAnyTrack) return null;

  const origin = sharedOrigin(timeline, resourceTimeline);
  const syncId = `run-timeline-${executionId}`;

  const markers: ChartMarker[] = [
    ...(timeline.levelChangeAtIso ? [{ atIso: timeline.levelChangeAtIso, label: 'Traffic jump' }] : []),
    ...(timeline.breakpointAtIso
      ? [{ atIso: timeline.breakpointAtIso, label: 'First objective violation', color: 'fail.6' }]
      : []),
  ];

  return (
    <section>
      <Title order={2} size="h4" mb="sm">
        Run timeline
      </Title>
      <Stack gap="sm">
        {throughputPlot && (
          <div>
            <Text size="sm" fw={650} mb={4}>
              Workload — offered vs achieved
            </Text>
            <TimelineChart plot={throughputPlot} height={130} origin={origin ?? undefined} syncId={syncId} markers={markers} />
          </div>
        )}
        {latencyPlot && (
          <div>
            <Text size="sm" fw={650} mb={4}>
              Latency — p95 vs objective
            </Text>
            <TimelineChart plot={latencyPlot} height={130} origin={origin ?? undefined} syncId={syncId} markers={markers} />
          </div>
        )}
        {cpuPlot && (
          <div>
            <Text size="sm" fw={650} mb={4}>
              CPU — system under test vs load generator
            </Text>
            <ResourceKindChart plot={cpuPlot} height={110} origin={origin ?? undefined} syncId={syncId} markers={markers} />
          </div>
        )}
        {memorySutPlot && (
          <div>
            <Text size="sm" fw={650} mb={4}>
              Memory — system under test
            </Text>
            <ResourceKindChart plot={memorySutPlot} height={110} origin={origin ?? undefined} syncId={syncId} markers={markers} />
          </div>
        )}
        {memoryGeneratorPlot && (
          <div>
            <Text size="sm" fw={650} mb={4}>
              Memory — load generator
            </Text>
            <ResourceKindChart plot={memoryGeneratorPlot} height={110} origin={origin ?? undefined} syncId={syncId} markers={markers} />
          </div>
        )}
      </Stack>
      {timeline.showsDerivedCaveat && (
        <Text size="xs" c="dimmed" mt="xs">
          Stage boundaries above are derived from the timeline rather than measured directly, so they
          are weaker evidence than a run whose stages the executor itself reported.
        </Text>
      )}
      {resourceTimeline.completenessStatus === 'PARTIAL' && (
        <Text size="xs" c="dimmed" mt="xs" className={classes.partial}>
          Resource series are partial{resourceTimeline.completenessReason ? `: ${resourceTimeline.completenessReason}.` : '.'} They do not describe the whole run.
        </Text>
      )}
    </section>
  );
}

function scopedPlot(plot: ResourceKindPlot, scope: string): ResourceKindPlot | null {
  const series = plot.series.filter((s) => s.scope === scope && s.points.length > 0);
  return series.length > 0 ? { ...plot, series } : null;
}

function sharedOrigin(timeline: TimelineEvidence, resourceTimeline: ResourceTimelineEvidence): number | null {
  const isoCandidates: string[] = [];
  for (const plot of timeline.plots) {
    for (const point of [...plot.points, ...plot.referencePoints]) {
      if (point.atIso) isoCandidates.push(point.atIso);
    }
  }
  for (const plot of resourceTimeline.plots) {
    for (const series of plot.series) {
      for (const point of series.points) {
        isoCandidates.push(point.atIso);
      }
    }
  }
  if (isoCandidates.length === 0) return null;
  return toEpochSeconds(isoCandidates.reduce((earliest, iso) => (iso < earliest ? iso : earliest)));
}
