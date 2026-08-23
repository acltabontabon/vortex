import { LineChart } from '@mantine/charts';
import type { ResourceKindPlot } from '../../api/run';
import { type ChartMarker, formatElapsed, toEpochSeconds, verticalMarkerLines } from './chartTime';

/** One color per scope, so "system under test" and "load generator" are visually distinct on sight
 *  rather than only by hovering a legend — the single most damaging confusion a resource chart can
 *  produce is reading one system's saturation as the other's. */
const SERIES_COLOR: Record<string, string> = {
  SYSTEM_UNDER_TEST: 'brand.6',
  LOAD_GENERATOR: 'neutral.6',
  DEPENDENCY: 'warn.6',
};

interface MergedRow {
  elapsedSeconds: number;
  atIso: string;
  [seriesKey: string]: number | string | null;
}

function seriesKey(signalId: string, providerId: string) {
  return `${providerId}:${signalId}`;
}

/** One row per timestamp across every series in the plot, so lines from different scopes/providers
 *  share one x-axis rather than each drawing against its own timeline. */
function merge(plot: ResourceKindPlot, origin: number): MergedRow[] {
  const byTime = new Map<string, MergedRow>();
  for (const series of plot.series) {
    const key = seriesKey(series.signalId, series.providerId);
    for (const point of series.points) {
      const existing = byTime.get(point.atIso);
      if (existing) {
        existing[key] = point.value;
      } else {
        byTime.set(point.atIso, {
          atIso: point.atIso,
          elapsedSeconds: toEpochSeconds(point.atIso) - origin,
          [key]: point.value,
        });
      }
    }
  }
  return Array.from(byTime.values()).sort((a, b) => a.elapsedSeconds - b.elapsedSeconds);
}

/**
 * One resource kind's chart — CPU, memory, a pool — with every scope that reported it overlaid on
 * the same axis when their units agree, which is what lets a reader see "was the load generator
 * saturated rather than the target service" in one glance instead of across two figures.
 *
 * <p>A sibling to {@link TimelineChart} rather than built on it: a resource plot can carry an
 * arbitrary number of named series (one per scope, or per named pool within a kind), where a k6
 * timeline plot only ever has one value series plus an optional reference — different enough shapes
 * that sharing one merge function would have meant branching inside it for every caller.
 */
export function ResourceKindChart({
  plot,
  height = 160,
  markers = [],
}: {
  plot: ResourceKindPlot;
  height?: number;
  /** Stage boundaries, a breakpoint, whatever instants this run's other charts already mark — drawn
   *  here too so a reader zooming in on one chart sees the others line up. */
  markers?: ChartMarker[];
}) {
  const withPoints = plot.series.filter((series) => series.points.length > 0);
  if (withPoints.length === 0) return null;

  const firstAtIso = withPoints
    .flatMap((series) => series.points)
    .reduce<string | null>(
      (earliest, point) => (earliest === null || point.atIso < earliest ? point.atIso : earliest),
      null,
    );
  if (firstAtIso === null) return null;
  const origin = toEpochSeconds(firstAtIso);

  const data = merge({ ...plot, series: withPoints }, origin);
  if (data.length === 0) return null;

  const lastElapsedSeconds = data[data.length - 1].elapsedSeconds;
  const markerLines = verticalMarkerLines(markers, origin, lastElapsedSeconds);

  const unitSymbol = withPoints[0].unitSymbol;
  const valueFormatter = (value: number) =>
    unitSymbol === '%' ? `${value}%` : `${value} ${unitSymbol}`;

  return (
    <LineChart
      h={height}
      data={data}
      dataKey="elapsedSeconds"
      series={withPoints.map((series) => ({
        name: seriesKey(series.signalId, series.providerId),
        label: series.scopeLabel,
        color: SERIES_COLOR[series.scope] ?? 'ai.6',
        ...(series.scope !== 'SYSTEM_UNDER_TEST' ? { strokeDasharray: '4 3' } : {}),
      }))}
      connectNulls={false}
      withDots={false}
      withLegend={withPoints.length > 1}
      strokeWidth={2}
      valueFormatter={valueFormatter}
      xAxisProps={{
        type: 'number',
        domain: ['dataMin', 'dataMax'],
        tickFormatter: formatElapsed,
      }}
      tooltipProps={{ labelFormatter: (label) => formatElapsed(Number(label)) }}
      referenceLines={markerLines}
    />
  );
}
