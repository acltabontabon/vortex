import { LineChart } from '@mantine/charts';
import { Text } from '@mantine/core';
import type { ResourceKindPlot } from '../../api/run';
import {
  type ChartMarker,
  formatBytes,
  formatElapsed,
  mixesCpuRatioWithPercent,
  toDisplayValue,
  toEpochSeconds,
  verticalMarkerLines,
} from './chartTime';

/** One color per scope, so "system under test", "load generator" and "load generator host" are
 *  visually distinct on sight rather than only by hovering a legend — the single most damaging
 *  confusion a resource chart can produce is reading one system's saturation as another's. */
const SERIES_COLOR: Record<string, string> = {
  SYSTEM_UNDER_TEST: 'brand.6',
  LOAD_GENERATOR: 'neutral.6',
  LOAD_GENERATOR_HOST: 'ai.6',
  DEPENDENCY: 'warn.6',
};

/** What each scope actually measures, in one line — shown as a caption beneath any chart that mixes
 *  scopes, so a reader does not have to guess what "load generator" versus "load generator host"
 *  means or where the number came from. */
const SCOPE_DESCRIPTION: Record<string, string> = {
  SYSTEM_UNDER_TEST: 'System under test — the service being tested.',
  LOAD_GENERATOR:
    "Load generator — the generator's own process or container, the narrowest measurement Vortex could isolate.",
  LOAD_GENERATOR_HOST:
    'Load generator host — the whole machine running the generator; supporting context, not the generator’s own limit.',
  DEPENDENCY: 'Dependency — something the service under test depends on.',
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
  const normalize = mixesCpuRatioWithPercent(plot);
  const byTime = new Map<string, MergedRow>();
  for (const series of plot.series) {
    const key = seriesKey(series.signalId, series.providerId);
    for (const point of series.points) {
      const value = toDisplayValue(series, point.value, normalize);
      const existing = byTime.get(point.atIso);
      if (existing) {
        existing[key] = value;
      } else {
        byTime.set(point.atIso, {
          atIso: point.atIso,
          elapsedSeconds: toEpochSeconds(point.atIso) - origin,
          [key]: value,
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
  origin: originOverride,
  syncId,
  domainMax,
}: {
  plot: ResourceKindPlot;
  height?: number;
  /** Stage boundaries, a breakpoint, whatever instants this run's other charts already mark — drawn
   *  here too so a reader zooming in on one chart sees the others line up. */
  markers?: ChartMarker[];
  /** A caller-supplied origin (epoch seconds) shared across several charts in one figure — see
   *  {@code TimelineChart}'s own doc on this. Falls back to this plot's own first point when
   *  omitted. */
  origin?: number;
  /** Recharts' own cross-chart hover sync id. Omitted (no sync) by default. */
  syncId?: string;
  /** The x-axis span (seconds from `origin`) every chart in the figure should share — see
   *  {@code TimelineChart}'s own doc on this. */
  domainMax?: number;
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
  const origin = originOverride ?? toEpochSeconds(firstAtIso);

  const data = merge({ ...plot, series: withPoints }, origin);
  if (data.length === 0) return null;

  // The system under test's own line is the one this chart exists to show; the generator and its
  // host are correlation context drawn on the same axis, not a second subject of equal weight — a
  // thinner, more transparent line keeps them readable without competing with the SUT's line for
  // the reader's eye.
  const scopeByName = new Map(withPoints.map((series) => [seriesKey(series.signalId, series.providerId), series.scope]));

  const lastElapsedSeconds = domainMax ?? data[data.length - 1].elapsedSeconds;
  const markerLines = verticalMarkerLines(markers, origin, lastElapsedSeconds);

  const scopesPresent = Array.from(new Set(withPoints.map((series) => series.scope)));

  /** Scopes carrying more than one line on this plot, whose lines a scope label cannot tell apart. */
  const sharedScopes = new Set(
    scopesPresent.filter(
      (scope) => withPoints.filter((series) => series.scope === scope).length > 1,
    ),
  );

  // Normalized to percent when this plot mixes a bare CPU ratio with an already-scaled one (see
  // mixesCpuRatioWithPercent) — every series was already converted onto that one shared unit inside
  // merge(), so the formatter below must agree, not fall back to whichever series happened to sort
  // first.
  const normalized = mixesCpuRatioWithPercent({ ...plot, series: withPoints });
  const unitSymbol = normalized ? '%' : withPoints[0].unitSymbol;
  // A ratio's own unit symbol is blank by domain design — "0.3" alone means nothing on an axis.
  // CPU is the one resource kind Vortex measures as a bare ratio (a fraction of one core), so this
  // is the one place that ratio is named rather than left silent; a future ratio kind would need
  // the same naming, not a guess at this ratio's unit.
  const ratioUnit = plot.kind === 'CPU' ? 'cores' : '';
  const valueFormatter = (value: number) => {
    if (unitSymbol === '%') return `${value}%`;
    if (unitSymbol === 'bytes') return formatBytes(value);
    if (unitSymbol === '' && ratioUnit) return `${value} ${ratioUnit}`;
    return `${value} ${unitSymbol}`;
  };

  return (
    <div>
      <LineChart
        h={height}
        data={data}
        dataKey="elapsedSeconds"
        series={withPoints.map((series) => ({
          name: seriesKey(series.signalId, series.providerId),
          // The scope alone names a line only while it identifies one. Three system-under-test
          // gauges on one plot all labelled "System under test" left the legend to fall back on the
          // series key, which rendered as "usage · usage · utilization" — three lines named after
          // the last word of their metric id. Where a scope is ambiguous the measurement's own name
          // is what distinguishes them; colour still carries the scope either way.
          label: sharedScopes.has(series.scope) ? series.seriesLabel : series.scopeLabel,
          color: SERIES_COLOR[series.scope] ?? 'ai.6',
          ...(series.scope !== 'SYSTEM_UNDER_TEST' ? { strokeDasharray: '4 3' } : {}),
        }))}
        connectNulls={false}
        withDots={false}
        withLegend={withPoints.length > 1}
        strokeWidth={2}
        lineProps={(series) =>
          scopeByName.get(series.name) !== 'SYSTEM_UNDER_TEST'
            ? { strokeWidth: 1.5, strokeOpacity: 0.6 }
            : {}
        }
        valueFormatter={valueFormatter}
        xAxisProps={{
          type: 'number',
          domain: domainMax !== undefined ? [0, domainMax] : ['dataMin', 'dataMax'],
          tickFormatter: formatElapsed,
        }}
        tooltipProps={{ labelFormatter: (label) => formatElapsed(Number(label)) }}
        {...(syncId ? { lineChartProps: { syncId } } : {})}
        referenceLines={markerLines}
      />
      {scopesPresent.length > 1 && (
        <Text size="xs" c="dimmed" mt={2}>
          {scopesPresent.map((scope) => SCOPE_DESCRIPTION[scope] ?? scope).join(' ')}
        </Text>
      )}
    </div>
  );
}
