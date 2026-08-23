import { useElementSize } from '@mantine/hooks';
import type { ShapeDto } from '../../api/tests';
import classes from './LoadShapeChart.module.css';

/**
 * The workload's own shape over time — an instrument glyph, not a chart-library panel.
 *
 * <p>Draws exactly what {@code ShapeDto} says and nothing else: a stepped silhouette, one flat
 * segment per stage at its own level for its own duration (a constant shape is one segment
 * spanning the whole width). No axis lines, no gridlines, no tooltip, no draw-in animation — the
 * two labels (the peak and the total duration) are the only elaboration, because a bare line with
 * no numbers on it is not evidence of anything. Deliberately not `@mantine/charts`: a ramp is a
 * real step function — `TestDefinitions.shape()` never interpolates between stage levels — and a
 * line/area chart's curve-fitting would draw motion within a stage that never happens.
 *
 * <p>All the arithmetic behind these numbers (the level and duration of every stage) came from the
 * domain, in `ShapeDto`. This file only ever turns already-real numbers into x/y coordinates and a
 * bar width — responsive layout, not business logic — the same kind of thing
 * `TrafficDistribution.tsx` already does turning a `shareFraction` into a CSS width.
 *
 * <p>Takes exactly one `ShapeDto` — never two — so there is no code path where an OPEN preview's
 * requests/sec and a CLOSED preview's VUs could end up scaled against each other. `unit` is the
 * one scale every stage in this chart is drawn against.
 */

const WIDE = { width: 640, height: 96, paddingLeft: 8, paddingRight: 8, paddingTop: 22, paddingBottom: 20 };

export function LoadShapeChart({ shape }: { shape: ShapeDto }) {
  const { ref, width: measuredWidth } = useElementSize<HTMLDivElement>();

  if (shape.stages.length === 0 || shape.totalDurationMillis <= 0) return null;

  const geometry = { ...WIDE, width: measuredWidth || WIDE.width };
  const trackWidth = geometry.width - geometry.paddingLeft - geometry.paddingRight;
  const baselineY = geometry.height - geometry.paddingBottom;
  const peakY = geometry.paddingTop;
  const peak = shape.peakLevelValue;

  const y = (level: number) => (peak <= 0 ? baselineY : baselineY - (level / peak) * (baselineY - peakY));
  const x = (fractionOfDuration: number) => geometry.paddingLeft + fractionOfDuration * trackWidth;

  // A staircase, not a line through the middle of each stage: two points per stage, the level held
  // flat across its own span (the first stage's own start point already fixes where the whole
  // shape begins — nothing to seed separately).
  let elapsed = 0;
  const points: string[] = [];
  for (const stage of shape.stages) {
    const startFraction = elapsed / shape.totalDurationMillis;
    elapsed += stage.durationMillis;
    const endFraction = elapsed / shape.totalDurationMillis;
    points.push(`${x(startFraction)},${y(stage.levelValue)}`);
    points.push(`${x(endFraction)},${y(stage.levelValue)}`);
  }

  const areaPoints = [`${x(0)},${baselineY}`, ...points, `${x(1)},${baselineY}`].join(' ');
  const linePoints = points.join(' ');

  const description = shape.ramping
    ? `Ramps to ${shape.peakLevelDisplay} across ${shape.stages.length} stages`
    : `Holds ${shape.peakLevelDisplay}`;

  return (
    <div ref={ref} className={classes.wrapper}>
      <svg
        className={classes.figure}
        viewBox={`0 0 ${geometry.width} ${geometry.height}`}
        role="img"
        aria-label={description}
        xmlns="http://www.w3.org/2000/svg"
      >
        <polygon className={classes.area} points={areaPoints} />
        <polyline className={classes.line} points={linePoints} />
        <text className={classes.peakLabel} x={geometry.paddingLeft} y={geometry.paddingTop - 8}>
          {shape.peakLevelDisplay}
        </text>
      </svg>
    </div>
  );
}
