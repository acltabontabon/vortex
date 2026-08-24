/** Shared, non-component pieces between {@code TimelineChart} and {@code ResourceKindChart} — split
 *  out so both chart components stay components-only exports (fast refresh needs that). */

import type { ResourceKindPlot, ResourceSeries } from '../../api/run';

export interface ChartMarker {
  atIso: string;
  label?: string;
  color?: string;
}

export function toEpochSeconds(atIso: string): number {
  return new Date(atIso).getTime() / 1000;
}

const BYTE_UNITS = ['bytes', 'KB', 'MB', 'GB', 'TB'];

/** Bytes at a scale a person can read, for a chart axis/tooltip — mirrors the backend's own
 *  `Bytes.display()` (powers of 1024) purely as a display convention, the same way this file's
 *  `formatElapsed` turns seconds into `mm:ss`. Never used to derive a value, only to print one. */
export function formatBytes(value: number): string {
  let scaled = Math.abs(value);
  let unitIndex = 0;
  while (scaled >= 1024 && unitIndex < BYTE_UNITS.length - 1) {
    scaled /= 1024;
    unitIndex += 1;
  }
  const signed = value < 0 ? -scaled : scaled;
  const number = unitIndex === 0 ? String(Math.round(signed)) : signed.toFixed(1);
  return `${number} ${BYTE_UNITS[unitIndex]}`;
}

export function formatElapsed(seconds: number): string {
  const whole = Math.max(0, Math.round(seconds));
  const minutes = Math.floor(whole / 60);
  const secs = whole % 60;
  return `${minutes}:${String(secs).padStart(2, '0')}`;
}

/**
 * Whether this plot mixes a bare CPU ratio with an already-scaled percentage.
 *
 * <p>CPU is the one resource kind reported in two genuinely different native units across
 * providers: Docker and the load generator measure a bare ratio (a fraction of one core), while
 * Actuator's own Micrometer gauges are deliberately normalized to a percentage before Vortex ever
 * sees them (see {@code ActuatorObservabilityProvider}). Both are correct in isolation — the bug is
 * plotting them unconverted on the same axis, which silently formats one provider's ratio as though
 * it were the other's percentage.
 */
export function mixesCpuRatioWithPercent(plot: ResourceKindPlot): boolean {
  if (plot.kind !== 'CPU') return false;
  const unitSymbols = new Set(plot.series.map((series) => series.unitSymbol));
  return unitSymbols.size > 1;
}

/** A series' raw value, converted onto the plot's one common display unit when the plot mixes
 *  units — a bare CPU ratio becomes a percentage (×100) so every line shares one scale; otherwise
 *  the value is untouched. */
export function toDisplayValue(series: ResourceSeries, rawValue: number, normalize: boolean): number {
  return normalize && series.unitSymbol === '' ? rawValue * 100 : rawValue;
}

/** Builds the `referenceLines` recharts wants from a list of instants, dropping any that fall
 *  outside the data actually plotted — a marker for a stage boundary past the run's own end would
 *  otherwise draw a line into empty space. Shared so a stage boundary looks the same on every chart
 *  it's drawn on. */
export function verticalMarkerLines(markers: ChartMarker[], origin: number, lastElapsedSeconds: number) {
  return markers
    .map((marker) => ({ ...marker, elapsedSeconds: toEpochSeconds(marker.atIso) - origin }))
    .filter((marker) => marker.elapsedSeconds >= 0 && marker.elapsedSeconds <= lastElapsedSeconds)
    .map((marker) => ({
      x: marker.elapsedSeconds,
      color: marker.color ?? 'neutral.6',
      strokeDasharray: '4 4',
      strokeWidth: 1,
      ...(marker.label ? { label: marker.label, labelPosition: 'insideTopRight' as const } : {}),
    }));
}
