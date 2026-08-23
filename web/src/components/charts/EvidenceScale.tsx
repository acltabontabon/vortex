import type { ReactNode } from 'react';
import { HoverCard, Text, Tooltip } from '@mantine/core';
import type { Capacity, CapacityRange, Marker, MarkerKind, Production } from '../../api/workspace';
import { shortRate } from '../../lib/testState';
import classes from './EvidenceScale.module.css';

/**
 * Overview's evidence instrument: what production sends this service, what it sustained while
 * meeting its objectives, and whether those two figures may be read together.
 *
 * <p>A sibling of {@link CapacityRangeFigure}, not a replacement for it — that component still draws
 * the same range on the Evidence tab and the home page, unchanged.
 *
 * <p>Both figures were already stated, full-size, in the fact grid above this section — repeating
 * them as a second pair of headline numbers here would be the same fact said twice in two fonts. So
 * this component never states them as headlines: "Production"/"Tested" are small labels, and the
 * values are small labels too, positioned on the instrument they belong to rather than standing
 * alone. Hovering a value reveals its elaboration (source, window, boundary confidence) without a
 * separate ⓘ — the value itself is the affordance.
 *
 * <p>Every label, value and dot for a given mark shares one x-coordinate — the mark's own `position`,
 * which `CapacityRange.position()` in vortex-core computes as `value / scaleTo` (0 at the axis
 * origin, 1 at the largest marker drawn). That is a real magnitude scale, not a two-point line: a
 * production peak at 35 of a 100 tested capacity sits about a third of the way along the track, not
 * flush against the left edge. Pinning the label/value text to the container's own edges — this
 * component's original layout — said something the domain never claimed (that the two marks are
 * equally far apart regardless of their actual values). Positioning them at the mark's real x is what
 * makes the picture match the number.
 *
 * <p>The one claim this figure is allowed to make about the relationship between the two marks:
 * production and tested capacity are always joined by a row of dots, never a solid line. A solid line
 * reads as "verified production headroom" whether or not that's true; a dotted one reads as "these
 * are two evidence points," which is the only thing this drawing is allowed to say — the actual
 * verdict (established, or refused and why) is stated in words beside the figure, never implied here.
 */

const WIDTH = 640;
const HEIGHT = 30;
const PAD_LEFT = 6;
const PAD_RIGHT = 6;
const TRACK_Y = 15;
const TICKS = 12;
const CONNECTOR_DOT_GAP = 8;

const SHORT_LABEL: Record<MarkerKind, string> = {
  PRODUCTION: 'Production',
  TESTED_CAPACITY: 'Tested',
  FIRST_FAILING: 'First failing',
};

/** Text anchored past either edge is inverted (`start`→right of the point, `end`→left of it) so it
 *  never overhangs off the instrument — the same threshold {@link CapacityRangeFigure} uses. */
type Anchor = 'start' | 'middle' | 'end';

function anchorFor(position: number): Anchor {
  if (position > 0.88) return 'end';
  if (position < 0.12) return 'start';
  return 'middle';
}

const ANCHOR_TRANSFORM: Record<Anchor, string> = {
  start: 'translateX(0)',
  middle: 'translateX(-50%)',
  end: 'translateX(-100%)',
};

const ANCHOR_HOVERCARD_POSITION: Record<Anchor, 'bottom-start' | 'bottom' | 'bottom-end'> = {
  start: 'bottom-start',
  middle: 'bottom',
  end: 'bottom-end',
};

export function EvidenceScale({
  range,
  production,
  capacity,
}: {
  range: CapacityRange;
  production: Production | null;
  capacity: Capacity | null;
}) {
  // Same gate CapacityRangeFigure uses: two marks minimum, decided by the domain, never guessed here.
  if (!range.renderable) return null;

  const trackWidth = WIDTH - PAD_LEFT - PAD_RIGHT;
  const x = (marker: Marker) => PAD_LEFT + marker.position * trackWidth;
  const leftPercent = (marker: Marker) => (x(marker) / WIDTH) * 100;

  const productionMark = range.markers.find((marker) => marker.kind === 'PRODUCTION');
  const capacityMark = range.markers.find((marker) => marker.kind === 'TESTED_CAPACITY');

  const connectorDots =
    productionMark && capacityMark
      ? dotsBetween(x(productionMark), x(capacityMark))
      : [];

  return (
    <div className={classes.scale}>
      <div className={classes.labels}>
        {range.markers.map((marker) => (
          <span
            key={marker.kind}
            className={classes.label}
            style={{ left: `${leftPercent(marker)}%`, transform: ANCHOR_TRANSFORM[anchorFor(marker.position)] }}
          >
            {SHORT_LABEL[marker.kind]}
          </span>
        ))}
      </div>

      <svg
        className={classes.figure}
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        aria-hidden="true"
        xmlns="http://www.w3.org/2000/svg"
      >
        <line
          className={classes.track}
          x1={PAD_LEFT}
          y1={TRACK_Y}
          x2={WIDTH - PAD_RIGHT}
          y2={TRACK_Y}
        />

        {Array.from({ length: TICKS + 1 }, (_, i) => PAD_LEFT + (i / TICKS) * trackWidth).map(
          (tx, i) => (
            <line key={i} className={classes.tick} x1={tx} y1={TRACK_Y - 3} x2={tx} y2={TRACK_Y + 3} />
          ),
        )}

        {/* Drawn only when tested capacity is the last mark — past a production mark it would read
            as a claim that traffic goes on climbing, which nothing here measured. */}
        {range.openEnded && (
          <path
            className={classes.open}
            d={`M${WIDTH - PAD_RIGHT - 7} ${TRACK_Y - 3.5} l7 3.5 l-7 3.5 z`}
          />
        )}

        {connectorDots.map((dx, i) => (
          <circle key={i} className={classes.connectorDot} cx={dx} cy={TRACK_Y} r={1.1} />
        ))}

        {range.markers.map((marker) => (
          <Tooltip
            key={marker.kind}
            label={`${marker.label} ${marker.displayWithUnit}`}
            openDelay={0}
            closeDelay={0}
            transitionProps={{ duration: 0 }}
            withArrow
            position="top"
          >
            <g>
              {/* A transparent circle well beyond the visible dot's own radius — a 4.5px mark is a
                  precise, frustrating target to actually hover, and the tooltip it carries needs a
                  hit area sized for a cursor, not for the dot's own visual weight. */}
              <circle className={classes.markHit} cx={x(marker)} cy={TRACK_Y} r={10} />
              <circle
                className={`${classes.mark} ${classes[markClass(marker.kind)]}`}
                cx={x(marker)}
                cy={TRACK_Y}
                r={4.5}
                pointerEvents="none"
              />
            </g>
          </Tooltip>
        ))}
      </svg>

      <div className={classes.values}>
        {productionMark && (
          <ValueChip
            leftPercent={leftPercent(productionMark)}
            anchor={anchorFor(productionMark.position)}
            value={shortRate(productionMark.displayWithUnit)}
            detail={
              production && (
                <>
                  <Text size="xs" fw={600} mb={2}>
                    Production traffic
                  </Text>
                  <Text size="xs">Peak: {production.peakRate}</Text>
                  {production.averageRate && <Text size="xs">Average: {production.averageRate}</Text>}
                  <Text size="xs" c="dimmed" mt={4}>
                    {production.fetched ? production.source : 'Entered by hand'}
                    {production.observedWindow && ` · ${production.observedWindow}`}
                  </Text>
                </>
              )
            }
          />
        )}

        {capacityMark && (
          <ValueChip
            leftPercent={leftPercent(capacityMark)}
            anchor={anchorFor(capacityMark.position)}
            value={shortRate(capacityMark.displayWithUnit)}
            detail={
              capacity && (
                <>
                  <Text size="xs">{capacity.boundaryStatusLabel}</Text>
                  <Text size="xs" c="dimmed">Boundary confidence {capacity.boundaryStrength}</Text>
                  <Text size="xs" c="dimmed" mt={4}>Measured {capacity.measuredAt}</Text>
                </>
              )
            }
          />
        )}
      </div>
    </div>
  );
}

/** Evenly-spaced dot positions between two x-coordinates, order-independent. */
function dotsBetween(a: number, b: number): number[] {
  const lo = Math.min(a, b);
  const hi = Math.max(a, b);
  const count = Math.max(2, Math.round((hi - lo) / CONNECTOR_DOT_GAP));
  return Array.from({ length: count + 1 }, (_, i) => lo + (i / count) * (hi - lo));
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

/**
 * A value, labelling the instrument it sits under — not a headline. Hovering it (when there's
 * elaboration to show) reveals a `HoverCard`; the value itself is the affordance, so there's no
 * separate ⓘ crowding a number this small. Positioned at its mark's own x, like {@link
 * EvidenceScale}'s top labels and the dot itself — see that component's doc comment for why.
 */
function ValueChip({
  leftPercent,
  anchor,
  value,
  detail,
}: {
  leftPercent: number;
  anchor: Anchor;
  value: string;
  detail?: ReactNode;
}) {
  const chip = (
    <span
      className={classes.value}
      style={{ left: `${leftPercent}%`, transform: ANCHOR_TRANSFORM[anchor] }}
    >
      {value}
    </span>
  );

  if (!detail) return chip;

  return (
    <HoverCard width={250} openDelay={150} position={ANCHOR_HOVERCARD_POSITION[anchor]} withArrow>
      <HoverCard.Target>{chip}</HoverCard.Target>
      <HoverCard.Dropdown>{detail}</HoverCard.Dropdown>
    </HoverCard>
  );
}
