import { useElementSize } from '@mantine/hooks';
import type { CapacityRange, Marker, MarkerKind } from '../../api/workspace';
import classes from './CapacityRangeFigure.module.css';

/**
 * What a service has been shown to do, on one line: what production sends it, the load it sustained
 * while meeting its objectives, and the load at which that stopped being true.
 *
 * <p>A projector, and nothing more. Which marks exist, whether a capacity figure may be shown at
 * all, whether production traffic may share this scale, and where each mark sits were all decided
 * by `CapacityRange` in vortex-core. This file draws them.
 *
 * <p>What it therefore never does, because the domain never said it:
 *
 * <ul>
 *   <li><strong>No shaded regions.</strong> There is no rule anywhere in Vortex that says a service
 *       is approaching its limit, so a gradient between the compliant and failing marks would
 *       render an invented conclusion more persuasively than the measured ones.
 *   <li><strong>No mark the range did not supply.</strong> A boundary the domain refused to quote,
 *       a failing edge from a non-monotonic run, a production figure measured in a different
 *       quantity — none of them arrive, and none of them are reconstructed.
 *   <li><strong>No verdict hue on production.</strong> Traffic production sends neither passed nor
 *       failed anything. It is the load the service is actually asked for.
 * </ul>
 *
 * <p>The figures are always printed beside the marks. A shape on a screen is not something anyone
 * can quote in a review.
 */

const SHORT_LABEL: Record<MarkerKind, string> = {
  PRODUCTION: 'Production peak',
  TESTED_CAPACITY: 'Tested capacity',
  FIRST_FAILING: 'First failing',
};

/** `width` is only the fallback used for the one frame before the wrapper's real width is measured
 *  — see `useElementSize` below. Height never scales with it: a wider column earns the line more
 *  room to place its marks and labels apart, not a proportionally taller (and so oddly enlarged)
 *  drawing. */
const WIDE = { width: 720, height: 124, axisY: 58, radius: 5 };
const COMPACT = { width: 320, height: 34, axisY: 17, radius: 4 };

const PADDING_LEFT = 16;
const PADDING_RIGHT = 16;

/** Per-character width estimates used only to keep two marks' text from touching — not exact
 *  metrics (SVG text can't be measured without a layout pass), but comfortably wide of what the
 *  value line (monospace, 12px) and the label line (sans-serif, 11px) actually render. */
const VALUE_CHAR_WIDTH = 7.3;
const LABEL_CHAR_WIDTH = 6.2;
const LABEL_GAP = 10;
const LABEL_ROWS = 2;
const ROW_STEP = 15;

export function CapacityRangeFigure({
  range,
  size = 'wide',
  emphasize,
}: {
  range: CapacityRange;
  size?: 'wide' | 'compact';
  /** The one mark to draw with a visible ring and heavier text — Breakpoint's own distinguishing
   *  touch, since the boundary is that test's whole point. Omitted (no emphasis) for every other
   *  kind that reuses this figure, Stress included — see `testVisualization.ts`. */
  emphasize?: MarkerKind;
}) {
  // Measures the wrapper `<div>`, not the `<svg>` itself — an SVG sized by `viewBox` alone has no
  // intrinsic width of its own to observe. Called unconditionally, before the early return below,
  // per React's rules of hooks.
  const { ref, width: measuredWidth } = useElementSize<HTMLDivElement>();

  // Two marks minimum. A single one sits alone on a full-width line, which implies the line spans
  // something — and the figure beside it already says everything the picture could.
  if (!range.renderable) return null;

  const defaults = size === 'wide' ? WIDE : COMPACT;
  // The one frame before ResizeObserver reports a real width, `measuredWidth` is 0 — fall back to
  // the constant rather than drawing a collapsed, zero-width figure for an instant.
  const geometry = { ...defaults, width: measuredWidth || defaults.width };
  const trackWidth = geometry.width - PADDING_LEFT - PADDING_RIGHT;
  const x = (marker: Marker) => PADDING_LEFT + marker.position * trackWidth;

  // The rightmost edge each text row's marks currently reach, so a label takes the first row it
  // fits on rather than overprinting the one before it. Marks land where the measurements put
  // them, and a service tested barely above its production peak is exactly the case somebody most
  // needs to read — two marks placed close together must still get their own row rather than
  // rendering as one smeared string.
  const rowExtents = new Array<number>(LABEL_ROWS).fill(-Infinity);

  const description = range.markers
    .map((marker) => `${marker.label} ${marker.displayWithUnit}`)
    .join(', ');

  return (
    <div ref={ref} className={classes.wrapper}>
      <svg
        className={classes.figure}
        viewBox={`0 0 ${geometry.width} ${geometry.height}`}
        role="img"
        aria-label={description}
        xmlns="http://www.w3.org/2000/svg"
      >
        <line
          className={classes.track}
          x1={PADDING_LEFT}
          y1={geometry.axisY}
          x2={geometry.width - PADDING_RIGHT}
          y2={geometry.axisY}
        />

        {/* Drawn only when tested capacity is the last mark. Past a production mark it would read as
            a claim that traffic goes on climbing, which nothing here measured. */}
        {range.openEnded && (
          <path
            className={classes.open}
            d={`M${geometry.width - PADDING_RIGHT - 9} ${geometry.axisY - 4} l9 4 l-9 4 z`}
          />
        )}

        {range.markers.map((marker) => {
          const cx = x(marker);

          // A mark at either extreme is anchored inward, so its text stays inside the drawing.
          const anchor =
            marker.position > 0.88 ? 'end' : marker.position < 0.12 ? 'start' : 'middle';

          // The actual footprint this mark's text occupies, given its anchor — 'middle' grows in
          // both directions from cx, 'start'/'end' grow only one way. Estimated from character
          // count rather than measured (SVG text has no layout pass to measure against), but wide
          // enough that two marks whose real text would touch always land on separate rows instead
          // of only looking that way from their center points.
          const textWidth = Math.max(
            marker.displayWithUnit.length * VALUE_CHAR_WIDTH,
            SHORT_LABEL[marker.kind].length * LABEL_CHAR_WIDTH,
          );
          const leftEdge = anchor === 'start' ? cx : anchor === 'end' ? cx - textWidth : cx - textWidth / 2;
          const rightEdge = anchor === 'end' ? cx : anchor === 'start' ? cx + textWidth : cx + textWidth / 2;

          const row = rowExtents.findIndex((extent) => leftEdge - LABEL_GAP > extent);
          const chosen = row === -1 ? LABEL_ROWS - 1 : row;
          rowExtents[chosen] = rightEdge;

          const valueY = geometry.axisY - 14 - chosen * ROW_STEP;
          const labelY = geometry.axisY + 18 + chosen * ROW_STEP;
          const emphasized = marker.kind === emphasize;

          return (
            <g key={marker.kind}>
              {emphasized && (
                <circle
                  className={classes.emphasisRing}
                  cx={cx}
                  cy={geometry.axisY}
                  r={geometry.radius + 4}
                />
              )}
              <circle
                className={`${classes.mark} ${classes[markClass(marker.kind)]}`}
                cx={cx}
                cy={geometry.axisY}
                r={geometry.radius}
              />
              {size === 'wide' && (
                <>
                  <text
                    className={`${classes.value} ${emphasized ? classes.emphasisValue : ''}`}
                    x={cx}
                    y={valueY}
                    textAnchor={anchor}
                  >
                    {marker.displayWithUnit}
                  </text>
                  <text
                    className={`${classes.label} ${emphasized ? classes.emphasisLabel : ''}`}
                    x={cx}
                    y={labelY}
                    textAnchor={anchor}
                  >
                    {SHORT_LABEL[marker.kind]}
                  </text>
                </>
              )}
            </g>
          );
        })}
      </svg>
    </div>
  );
}

function markClass(kind: MarkerKind): 'production' | 'capacity' | 'failing' {
  switch (kind) {
    case 'PRODUCTION':
      return 'production';
    case 'TESTED_CAPACITY':
      return 'capacity';
    case 'FIRST_FAILING':
      return 'failing';
  }
}
