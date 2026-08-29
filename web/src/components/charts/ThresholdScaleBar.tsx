import classes from './ThresholdScaleBar.module.css';

export interface ScaleMarker {
  value: number;
  label: string;
  kind: 'baseline' | 'production' | 'objective';
}

/**
 * A compact min-to-max scale placing baseline, production and the proposed objective on one line —
 * the "faster ... slower" figure from the Threshold Assistant's own design direction, subtle enough
 * to sit beside a single threshold row rather than needing its own chart. Purely a rendering of
 * numbers the caller already has; nothing here computes a comparison.
 *
 * <p>Renders nothing below two markers — a bar with only one point on it has nothing to compare.
 */
export function ThresholdScaleBar({ markers }: { markers: ScaleMarker[] }) {
  if (markers.length < 2) return null;

  const width = 220;
  const height = 34;
  const trackY = 10;
  const values = markers.map((m) => m.value);
  const rawMin = Math.min(...values);
  const rawMax = Math.max(...values);
  const pad = (rawMax - rawMin) * 0.15 || rawMax * 0.1 || 1;
  const min = Math.max(0, rawMin - pad);
  const max = rawMax + pad;
  const span = max - min || 1;

  const x = (value: number) => ((value - min) / span) * width;

  return (
    <svg
      className={classes.scale}
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      role="img"
      aria-label={markers.map((m) => `${m.kind}: ${m.label}`).join(', ')}
    >
      <line className={classes.track} x1={0} y1={trackY} x2={width} y2={trackY} />
      {markers.map((marker) => {
        const cx = x(marker.value);
        const tone = classes[marker.kind];
        return (
          <g key={marker.kind}>
            {marker.kind === 'objective' ? (
              <path
                className={`${classes.mark} ${tone}`}
                d={`M${cx} ${trackY - 8} l5 8 l-10 0 z`}
              />
            ) : (
              <circle className={`${classes.mark} ${tone}`} cx={cx} cy={trackY} r={3.5} />
            )}
            <text
              className={`${classes.label} ${tone}`}
              x={Math.min(Math.max(cx, 18), width - 18)}
              y={height - 2}
              textAnchor="middle"
            >
              {marker.label}
            </text>
          </g>
        );
      })}
    </svg>
  );
}
