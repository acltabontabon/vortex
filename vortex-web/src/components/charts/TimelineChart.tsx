import { LineChart } from '@mantine/charts';
import type { TimelinePlot, TimelinePoint } from '../../api/run';
import { type ChartMarker, formatElapsed, toEpochSeconds, verticalMarkerLines } from './chartTime';

/**
 * Draws one timeline plot (throughput, latency or error rate) with a real charting library.
 *
 * <p>Unlike the load axis (see {@code ServerSvg}), this chart has no PDF counterpart to stay in
 * lock-step with: the exported report draws {@code SeriesPlot} straight to PDF primitives in
 * {@code PdfChartRenderer}, independently of whatever the browser does with the same raw points.
 * Both still start from the one {@code SeriesPlot} in vortex-core, so the browser and the printed
 * report never disagree about the numbers themselves — only about how they're drawn.
 *
 * <p>The accompanying data table (see {@code TimelineSection}) stays the other half of the chart,
 * per ADR-021 — a shape on a screen is not something anyone can quote in a review.
 */

interface MergedRow {
  elapsedSeconds: number;
  value: number | null;
  reference: number | null;
}

/**
 * The run's own start, in epoch seconds — every plot built from the same underlying series shares
 * this instant, which is what lets a breakpoint marker line up at the same x on each of them: it is
 * converted against this one origin rather than something each chart derives for itself.
 */
function originOf(points: TimelinePoint[], referencePoints: TimelinePoint[]): number | null {
  const t0 = points.find((p) => p.atIso)?.atIso ?? referencePoints.find((p) => p.atIso)?.atIso;
  return t0 ? toEpochSeconds(t0) : null;
}

/**
 * Combines the achieved series and the reference series (e.g. offered rate) into one row per
 * timestamp, keyed by the ISO instant the server already deduplicates on. A row with no match on
 * the other side simply leaves that field absent, which reads to the chart the same way a `null`
 * gap point does — no line is drawn through it.
 */
function merge(points: TimelinePoint[], referencePoints: TimelinePoint[], origin: number): MergedRow[] {
  const byTime = new Map<string, MergedRow & { atIso: string }>();

  for (const point of points) {
    if (!point.atIso) continue;
    byTime.set(point.atIso, {
      atIso: point.atIso,
      elapsedSeconds: toEpochSeconds(point.atIso) - origin,
      value: point.value,
      reference: null,
    });
  }
  for (const point of referencePoints) {
    if (!point.atIso) continue;
    const existing = byTime.get(point.atIso);
    if (existing) {
      existing.reference = point.value;
    } else {
      byTime.set(point.atIso, {
        atIso: point.atIso,
        elapsedSeconds: toEpochSeconds(point.atIso) - origin,
        value: null,
        reference: point.value,
      });
    }
  }
  return Array.from(byTime.values()).sort((a, b) => a.elapsedSeconds - b.elapsedSeconds);
}

function formatValue(unitSymbol: string) {
  return (value: number) => (unitSymbol === '%' ? `${value}%` : `${value} ${unitSymbol}`);
}

export function TimelineChart({
  plot,
  height = 200,
  markAtIso,
  markLabel,
  markers,
  origin: originOverride,
  syncId,
}: {
  plot: TimelinePlot;
  height?: number;
  /** One instant every plot in the figure marks in common — a compliance breakpoint, a traffic
   *  jump, whatever the figure's own annotation means (see {@code TimeSeriesFigure}). This chart has
   *  no opinion about which; it just draws the line. Ignored (no marker drawn) when null, outside
   *  this plot's own span, or absent. */
  markAtIso?: string | null;
  /** Text for the marker's label. Pass this on only one of the figure's charts — repeating it on
   *  every chart would say the same thing three times for one line that already lines up visually. */
  markLabel?: string;
  /** Additional markers beyond the single `markAtIso` — e.g. every workload stage boundary. Merged
   *  with `markAtIso` rather than replacing it, so existing callers are unaffected. */
  markers?: ChartMarker[];
  /** A caller-supplied origin (epoch seconds), for a figure with several charts that must all read
   *  elapsed time from the exact same instant — e.g. {@code RunTimeline}'s synchronized tracks.
   *  Falls back to this plot's own first point when omitted, unchanged from before this existed. */
  origin?: number;
  /** Recharts' own cross-chart hover sync id — every chart sharing one value gets one moving cursor.
   *  Omitted (no sync) by default, so existing callers are unaffected. */
  syncId?: string;
}) {
  if (!plot.hasData) return null;

  const origin = originOverride ?? originOf(plot.points, plot.referencePoints);
  if (origin === null) return null;

  const data = merge(plot.points, plot.referencePoints, origin);
  if (data.length === 0) return null;

  const hasReference = plot.referencePoints.some((p) => p.value !== null);
  const valueFormatter = formatValue(plot.unitSymbol);

  const lastElapsedSeconds = data[data.length - 1].elapsedSeconds;
  const allMarkers: ChartMarker[] = [
    ...(markAtIso ? [{ atIso: markAtIso, label: markLabel }] : []),
    ...(markers ?? []),
  ];
  const markerLines = verticalMarkerLines(allMarkers, origin, lastElapsedSeconds);

  return (
    <LineChart
      h={height}
      data={data}
      dataKey="elapsedSeconds"
      series={[
        { name: 'value', label: plot.label, color: 'brand.6' },
        ...(hasReference
          ? [{ name: 'reference', label: 'Offered', color: 'neutral.6', strokeDasharray: '4 3' }]
          : []),
      ]}
      connectNulls={false}
      withDots={false}
      withLegend={hasReference}
      strokeWidth={2}
      valueFormatter={valueFormatter}
      xAxisProps={{
        type: 'number',
        domain: ['dataMin', 'dataMax'],
        tickFormatter: formatElapsed,
      }}
      tooltipProps={{ labelFormatter: (label) => formatElapsed(Number(label)) }}
      {...(syncId ? { lineChartProps: { syncId } } : {})}
      referenceLines={[
        ...(plot.referenceLevel !== null
          ? [
              {
                y: plot.referenceLevel,
                color: 'fail.6',
                strokeDasharray: '5 3',
                label: valueFormatter(Math.round(plot.referenceLevel)),
              },
            ]
          : []),
        ...markerLines,
      ]}
    />
  );
}
