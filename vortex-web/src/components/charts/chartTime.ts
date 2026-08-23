/** Shared, non-component pieces between {@code TimelineChart} and {@code ResourceKindChart} — split
 *  out so both chart components stay components-only exports (fast refresh needs that). */

export interface ChartMarker {
  atIso: string;
  label?: string;
  color?: string;
}

export function toEpochSeconds(atIso: string): number {
  return new Date(atIso).getTime() / 1000;
}

export function formatElapsed(seconds: number): string {
  const whole = Math.max(0, Math.round(seconds));
  const minutes = Math.floor(whole / 60);
  const secs = whole % 60;
  return `${minutes}:${String(secs).padStart(2, '0')}`;
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
