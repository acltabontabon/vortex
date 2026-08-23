import type { CSSProperties } from 'react';
import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { Text, Title, Tooltip, UnstyledButton } from '@mantine/core';
import {
  IconChartBar,
  IconChartHistogram,
  IconChartLine,
  IconClockHour4,
  IconCode,
  IconPlayerPlay,
  IconServer,
} from '@tabler/icons-react';
import { useIsMutating } from '@tanstack/react-query';
import { useReducedMotion } from 'motion/react';
import type { Readiness, ReadinessItem } from '../../api/workspace';
import { SignalDrawer } from './SignalDrawer';
import classes from './ServiceVortex.module.css';

/** How long a satisfied signal stays on screen being drawn in. Matches `signalConsumed` in the CSS. */
const CONSUME_MS = 900;
/** And how long a newly reachable one takes to move inward. Matches `signalEntered`. */
const ENTER_MS = 700;

/**
 * What a service looks like before it can measure anything.
 *
 * <p>A service with nowhere to send traffic has no figures to state, no tests worth listing and no
 * history to show, and rendering the configured page anyway leaves three separate blocks each
 * saying "nothing" on its own. This says it once, as the product's own shape: the signals Vortex
 * needs — a target, an API description, a workload, objectives, a production baseline — circling the
 * funnel that consumes them.
 *
 * <h2>Three independent dimensions</h2>
 *
 * <p>A signal is unavoidable or optional ({@code effectivelyRequired}), done or not
 * ({@code satisfied}), and possible or not yet ({@code available}). All three come from
 * {@code ProjectReadiness}; none is decided here. They are rendered separately on purpose — weight
 * carries unavoidable-vs-optional, distance and stillness carry available-vs-blocked — because
 * collapsing any two of them produces a screen that either greys out the thing somebody came to do
 * or offers a form that cannot be filled in.
 *
 * <p>"Unavoidable" is not "blocks a run": objectives gate no run and decide every verdict, and an
 * API import gates no run but is the only way to reach the workload that does. A production
 * baseline is the counter-case — it matters as much as either and stays optional, because a service
 * that is not serving anything yet cannot have one. See {@code ProjectReadiness.Kind}.
 *
 * <h2>What it will not do</h2>
 *
 * <p>It will not become a wizard. Prerequisites are a graph, not an order: an environment, an API
 * description and objectives are all reachable from the start and in any sequence, and only the
 * workload branch waits on anything. It will not dead-end a blocked signal either — clicking one
 * says what is missing and offers the way to it. And it will not celebrate: the motion says *this
 * was incorporated*, which is a statement about system state, and the moment it becomes a reward it
 * is lying about what happened.
 */
export function ServiceVortex({
  readiness,
  serviceId,
}: {
  readiness: Readiness;
  serviceId: string;
}) {
  const headingId = useId();
  // `useReducedMotion` returns null before it has resolved, so compare rather than coerce.
  const reducedMotion = useReducedMotion() === true;

  const [openKey, setOpenKey] = useState<string | null>(null);
  const [hoverKey, setHoverKey] = useState<string | null>(null);
  const consuming = useConsumedSignals(readiness, reducedMotion);
  const entering = useEnteringSignals(readiness, reducedMotion);

  /*
   * Any mutation in flight can only be the open drawer's — nothing else on this screen writes. Read
   * from the query client rather than threaded back out of the configuration forms, which would mean
   * a save callback on every one of them for a state that lasts a few hundred milliseconds.
   */
  const saving = useIsMutating() > 0;

  /*
   * Closed by persisted state rather than by a save callback: the drawer resolves to nothing the
   * moment the thing it configures is actually satisfied, which is the same instant the signal
   * starts being drawn in. Derived rather than pushed through an effect so there is no window in
   * which the drawer is open over an item that is already done.
   */
  const openItem =
    readiness.items.find((item) => item.key === openKey && !item.satisfied) ?? null;

  /*
   * One ring, in the domain's own order.
   *
   * <p>It was two — unavoidable close in, optional further out — and two rings of different radii
   * read as scattered points rather than as an orbit, which is the one thing this figure has to
   * say. The hierarchy moved onto the nodes themselves instead: an unavoidable signal is larger and
   * darker than an optional one, which says the same thing without breaking the circle.
   *
   * <p>RESULT is not on it, because "Test executed" is not a signal you configure — it becomes true
   * once a run has happened, and offering to "set it up" beside the others would misdescribe it. It
   * is also the one item this screen never has to show: by the time it matters, the ordinary
   * workbench is here.
   *
   * <p>Nor is anything that merely narrows a signal still outstanding. "Average-load workload
   * defined" is not a second thing to do on a service with no workload at all — it is the same
   * composer, and two nodes opening one form teach nothing. `distinctFromWhatItNarrows` in the
   * domain decides that; once a workload exists it becomes its own action and appears.
   */
  const orbit = useMemo(
    () =>
      readiness.items.filter(
        (item) =>
          item.kind !== 'RESULT' &&
          item.distinct &&
          (!item.satisfied || consuming.has(item.key)),
      ),
    [readiness.items, consuming],
  );

  // While a blocked signal is under the pointer or the caret, its prerequisites are what the eye
  // should find — contextual and temporary, never a dependency graph drawn across the screen.
  const hovered = readiness.items.find((item) => item.key === hoverKey) ?? null;
  const highlighted = new Set(hovered && !hovered.available ? hovered.blockedBy : []);

  const nodeState: NodeState = {
    consuming,
    entering,
    highlighted,
    openKey,
    saving,
    onOpen: setOpenKey,
    onHover: setHoverKey,
  };

  return (
    <section
      className={classes.vortex}
      aria-labelledby={headingId}
      data-reduced-motion={reducedMotion ? 'true' : undefined}
    >
      <Title order={2} id={headingId} className={classes.headline}>
        Nothing to measure yet
      </Title>
      <Text className={classes.body}>Configure the signals Vortex needs to understand this service.</Text>

      <div className={classes.stage}>
        <Swirl settled={readiness.satisfiedCount} disturbed={consuming.size > 0} />

        <Orbit items={orbit} state={nodeState} />
      </div>

      <SignalDrawer
        item={openItem}
        serviceId={serviceId}
        items={readiness.items}
        opened={openItem !== null}
        onClose={() => setOpenKey(null)}
        onOpenOther={setOpenKey}
      />
    </section>
  );
}

/** Everything the nodes share, gathered so an orbit does not take nine props of its own. */
interface NodeState {
  consuming: Set<string>;
  entering: Set<string>;
  highlighted: Set<string>;
  openKey: string | null;
  saving: boolean;
  onOpen: (key: string) => void;
  onHover: (key: string | null) => void;
}

/**
 * One ring of signals.
 *
 * <p>A list, not a scatter: absolutely positioning `<li>` costs nothing in the accessibility tree,
 * and it is what lets tab order be the domain's own order with no `tabindex` anywhere.
 */
function Orbit({ items, state }: { items: ReadinessItem[]; state: NodeState }) {
  if (items.length === 0) return null;

  return (
    <ul className={classes.rim} aria-label="Signals this service still needs">
      {items.map((item, index) => (
        <li
          key={item.key}
          className={classes.shard}
          data-consuming={state.consuming.has(item.key) ? 'true' : undefined}
          data-entering={state.entering.has(item.key) ? 'true' : undefined}
          style={station(index, items.length, item.available)}
        >
          <Signal item={item} state={state} />
        </li>
      ))}
    </ul>
  );
}

/**
 * One signal, as the control that configures it.
 *
 * <p>A button rather than a link, because it opens a drawer over this page instead of navigating —
 * the whole point being that setting a service up never takes you off it. A blocked one is an
 * ordinary button too: not disabled, not dimmed, not dashed. It does something — it explains what is
 * in the way and offers the way to it — and dressing a control that works as one that does not is
 * both a lie and, for anyone who cannot see the difference in contrast, a dead end.
 *
 * <p>The domain's own sentence rides in the accessible name, so the control announces a complete
 * thought — "Workload defined, not available yet. A workload spreads traffic across the things a
 * service can do, so Vortex has to know what those are first." — rather than a bare label. Putting
 * seven of those on screen around a ring would be unreadable, and hiding them behind hover would
 * gate the explanation on owning a pointer.
 */
function Signal({ item, state }: { item: ReadinessItem; state: NodeState }) {
  const selected = state.openKey === item.key;

  return (
    <Tooltip
      label={item.available ? item.nextStep : item.blockedReason}
      openDelay={300}
      withArrow
      multiline
      maw={320}
      // Focus is off by default in Mantine, which would leave a keyboard user with no way to reach
      // the explanation at all — and for a blocked signal the explanation is the entire point.
      events={{ hover: true, focus: true, touch: false }}
    >
      <UnstyledButton
        className={classes.signal}
        onClick={() => state.onOpen(item.key)}
        onMouseEnter={() => state.onHover(item.key)}
        onMouseLeave={() => state.onHover(null)}
        onFocus={() => state.onHover(item.key)}
        onBlur={() => state.onHover(null)}
        data-state={nodeStateName(item, selected, state.saving)}
        data-required={item.effectivelyRequired ? 'true' : undefined}
        data-prerequisite={state.highlighted.has(item.key) ? 'true' : undefined}
      >
      {/*
        Shape, not just colour: an open ring for something waiting to be done, a hollow dot set back
        for something not possible yet. State that only a hue distinguishes is state some people
        cannot read.
      */}
        {/*
          An instrument, not a badge. The tile is the only surface a node has — the label sits bare
          beneath it, the way it does on a diagram — and shape carries state so nothing here is
          distinguishable by hue alone: a solid tile is reachable, a dashed one is not yet.
        */}
        <span className={classes.tile} aria-hidden="true">
          <SignalIcon signalKey={item.key} />
        </span>
        <span className={classes.label}>{item.label}</span>
        {/* Secondary metadata, not a tag on a form field — lowercase, quiet, and only where it says
            something. There is no matching "required" chip; weight and position carry that. */}
        {/* The explicit space is not decoration: accessible names concatenate element contents with
            no separator, so without it this announces "API importedoptional". */}
        {!item.effectivelyRequired && (
          <>
            {' '}
            <span className={classes.meta}>optional</span>
          </>
        )}
        {/*
          State in the name, reason in the description. The tooltip supplies the reason to everyone —
          it is linked by `aria-describedby` and opens on focus as well as hover — so repeating it
          here would have a screen reader say the whole sentence twice.
        */}
        {!item.available && <span className="visually-hidden">, not available yet</span>}
      </UnstyledButton>
    </Tooltip>
  );
}

/**
 * The face of each signal.
 *
 * <p>Keyed on the domain's stable key rather than its label, and drawn from the workbench's own icon
 * set rather than invented — each one says what kind of *thing* the signal is, so the ring reads as
 * instrumentation being drawn in rather than as a row of identical chips. A key nobody has given a
 * face to falls back to the generic one instead of rendering a hole.
 */
function SignalIcon({ signalKey }: { signalKey: string }) {
  const size = 19;
  switch (signalKey) {
    case 'API_IMPORTED':
      return <IconCode size={size} stroke={1.6} />;
    case 'ENVIRONMENT':
      return <IconServer size={size} stroke={1.6} />;
    case 'WORKLOAD':
      return <IconChartHistogram size={size} stroke={1.6} />;
    case 'AVERAGE_LOAD_WORKLOAD':
      return <IconChartBar size={size} stroke={1.6} />;
    case 'OBJECTIVES':
      return <IconClockHour4 size={size} stroke={1.6} />;
    case 'PRODUCTION_TRAFFIC':
      return <IconChartLine size={size} stroke={1.6} />;
    default:
      return <IconPlayerPlay size={size} stroke={1.6} />;
  }
}

/**
 * The one name for what a node is doing, so the CSS has a single attribute to switch on rather than
 * four booleans to combine.
 *
 * <p>Ordered by what matters most to somebody looking at it: not being possible outranks being
 * open, which outranks being mid-save.
 */
function nodeStateName(item: ReadinessItem, selected: boolean, saving: boolean): string {
  if (!item.available) return 'blocked';
  if (selected && saving) return 'saving';
  if (selected) return 'selected';
  return 'available';
}

/**
 * Which signals became satisfied just now, and are therefore still being drawn in.
 *
 * <p>Driven entirely by persisted readiness rather than by a save callback, which is what makes the
 * animation honest: nothing is ever animated into the funnel that the server has not already
 * confirmed. It also means the effect works from any source — the drawer, the Configuration page in
 * another tab, an edit to `vortex.yaml` on disk.
 *
 * <p>Under reduced motion the set stays empty and the signal simply stops being rendered, so the
 * state transition is complete and correct with no motion at all.
 */
function useConsumedSignals(readiness: Readiness, reducedMotion: boolean): Set<string> {
  return useTransitionedSignals(
    readiness.items.filter((item) => item.satisfied).map((item) => item.key),
    reducedMotion,
    CONSUME_MS,
  );
}

/**
 * And which just became possible, because something they needed was configured.
 *
 * <p>The other half of the same idea: a signal that has been sitting outside the field moves into it
 * the moment its prerequisite is persisted. Nothing announces it — the node gains contrast, moves in
 * and starts drifting, which is the whole message.
 */
function useEnteringSignals(readiness: Readiness, reducedMotion: boolean): Set<string> {
  return useTransitionedSignals(
    readiness.items.filter((item) => item.available).map((item) => item.key),
    reducedMotion,
    ENTER_MS,
  );
}

/**
 * The keys that just entered {@code members}, held for {@code holdMs} so a transition can run.
 *
 * <p>The first render only establishes the baseline. Everything already true when this screen opened
 * was configured before anybody arrived, and replaying it as it loads would be theatre.
 */
function useTransitionedSignals(
  members: string[],
  reducedMotion: boolean,
  holdMs: number,
): Set<string> {
  const previous = useRef<Set<string> | null>(null);
  const [active, setActive] = useState<Set<string>>(new Set());
  const signature = members.join(',');

  useEffect(() => {
    const now = new Set(signature ? signature.split(',') : []);
    const before = previous.current;
    previous.current = now;

    if (before === null || reducedMotion) return;

    const fresh = [...now].filter((key) => !before.has(key));
    if (fresh.length === 0) return;

    setActive((current) => new Set([...current, ...fresh]));
    const timer = setTimeout(
      () => setActive((current) => new Set([...current].filter((key) => !fresh.includes(key)))),
      holdMs,
    );
    return () => clearTimeout(timer);
  }, [signature, reducedMotion, holdMs]);

  return active;
}

/**
 * The funnel itself — decoration, and out of the accessibility tree entirely.
 *
 * <p>`disturbed` is the one thing here that is not ambient: a single radial pulse while something is
 * being drawn in, so the figure acknowledges the event instead of ignoring it.
 */
function Swirl({ settled, disturbed }: { settled: number; disturbed: boolean }) {
  return (
    <div
      className={classes.swirl}
      data-vortex-swirl
      data-disturbed={disturbed ? 'true' : undefined}
      aria-hidden="true"
    >
      {/* A square box as tall as the stage, so everything inside can be sized as a fraction of one
          thing and the whole figure scales with the space it is given rather than a fixed clamp. */}
      <div className={classes.funnel}>
        <div className={classes.sweep} />
        <div className={classes.sweepInner} />
        <div className={classes.core} />
        <div className={classes.pulse} />

        <svg className={classes.rings} viewBox="0 0 130 100" preserveAspectRatio="xMidYMid meet">
          <g
            fill="none"
            stroke="currentColor"
            strokeLinecap="round"
            vectorEffect="non-scaling-stroke"
          >
            {FLARES.map((d, index) => (
              <path key={`flare-${index}`} d={d} opacity="0.26" strokeWidth="0.7" />
            ))}
            {STROKES.map((stroke, index) => (
              <path key={index} d={stroke.d} opacity={stroke.opacity} strokeWidth={stroke.width} />
            ))}
          </g>
        </svg>

        {/* What the funnel has already taken in. Placed by golden angle, never at random — a figure
            that reshuffles itself on every render is noise pretending to be life. */}
        {Array.from({ length: settled }, (_, index) => (
          <span key={index} className={classes.mote} style={mote(index)} />
        ))}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- the funnel

/*
 * Drawn to a 130 x 100 box, because the shape is wider than it is tall.
 *
 * <p>Spirals, not concentric rings. That is the entire difference between this and a lampshade: a
 * stack of closed ellipses gives you a woven basket no matter how you taper it, because the eye can
 * trace no path from the rim to the throat. One continuous line that winds inward and downward is
 * read as rotation, and rotation is what a vortex is. Everything below serves that one line.
 */
const VIEW_W = 130;
const CENTRE_X = VIEW_W / 2;

const MOUTH_RX = 57; // half-width at the rim, in viewBox units
const RIM_CY = 27; // where the rim sits
const THROAT_CY = 90; // and where the line finally closes
/** Perspective: how flat a turn looks from this viewing angle. Constant all the way down. */
const FLATTEN = 0.33;
const TURNS = 5.4; // full revolutions from rim to throat
const SAMPLES = 190; // points per spiral — enough that straight segments read as curve

/** Three passes over the same descent, offset in phase, so the turns interleave the way ink does. */
const PASSES = [
  { phase: 0, scale: 1, width: 0.95, alpha: 1 },
  { phase: 2.1, scale: 0.93, width: 0.8, alpha: 0.72 },
  { phase: 4.2, scale: 0.86, width: 0.7, alpha: 0.5 },
];

/** Each pass is cut into segments so the line can darken as it descends into the throat. */
const SEGMENTS = [
  { from: 0, to: 0.3, opacity: 0.3 },
  { from: 0.28, to: 0.56, opacity: 0.42 },
  { from: 0.54, to: 0.8, opacity: 0.55 },
  { from: 0.78, to: 1, opacity: 0.72 },
];

interface Stroke {
  d: string;
  opacity: number;
  width: number;
}

/**
 * A point on the descent.
 *
 * <p>Radius falls off faster than depth advances, which is what keeps the rim broad and the throat
 * tight; the sideways lean stops the axis being a ruled vertical. The `sin` term is the perspective
 * — the same turn is drawn below its own centre on the near side and above it on the far side, and
 * that alternation is the only reason a flat curve reads as something you are looking into.
 */
function pointAt(u: number, phase: number, scale: number): [number, number] {
  const theta = phase + u * TURNS * Math.PI * 2;
  const radius = MOUTH_RX * scale * (1 - u) ** 1.5;
  const depth = RIM_CY + (THROAT_CY - RIM_CY) * u ** 0.92;
  const lean = 4 * Math.sin(u * Math.PI * 1.15);

  return [
    CENTRE_X + lean + radius * Math.cos(theta),
    depth + radius * FLATTEN * Math.sin(theta),
  ];
}

function buildStrokes(): Stroke[] {
  const strokes: Stroke[] = [];

  for (const pass of PASSES) {
    for (const segment of SEGMENTS) {
      const points: string[] = [];
      const count = Math.max(2, Math.round(SAMPLES * (segment.to - segment.from)));

      for (let step = 0; step <= count; step += 1) {
        const u = segment.from + ((segment.to - segment.from) * step) / count;
        const [x, y] = pointAt(u, pass.phase, pass.scale);
        points.push(`${x.toFixed(2)} ${y.toFixed(2)}`);
      }

      strokes.push({
        d: `M${points.join('L')}`,
        opacity: segment.opacity * pass.alpha,
        width: pass.width,
      });
    }
  }

  return strokes;
}

const STROKES = buildStrokes();

/**
 * The strokes that arrive at the rim rather than belonging to the funnel — what is being drawn in,
 * on its way there. They run *outside* the widest turn and off the edge of the box (`.rings` is
 * `overflow: visible` for exactly this); a flare that crosses the mouth stops reading as inflow and
 * starts reading as a stray line through the drawing.
 */
const FLARES = [
  'M-34 30 C -16 18, 4 10, 24 8',
  'M-38 44 C -20 34, -2 23, 16 17',
  'M-30 17 C -14 9, 6 4, 28 3',
  'M164 24 C 146 13, 124 6, 102 5',
  'M168 37 C 152 28, 134 18, 118 14',
];

// ---------------------------------------------------------------- geometry

/*
 * Two orbits, and the distance between them is the hierarchy. What blocks a run sits close enough to
 * the funnel to read as attached to it; what only strengthens the evidence sits out at the edge of
 * the composition. Nothing is randomly placed and nothing travels: a shard that revolves at this
 * radius crosses the cursor at ~20px/s, and these are targets whose entire purpose is to be clicked.
 */
/*
 * The ring, as a fraction of the stage. Wider than tall because the stage is, so on screen this
 * traces a circle rather than the tall oval one radius would give. The funnel is sized to sit inside
 * it with clearance all the way round (`.funnel` in the CSS) — that clearance is the whole reason
 * this can be a ring at all rather than two clusters either side of the drawing.
 */
const ORBIT_RX = 39; // % of the stage's width
const ORBIT_RY = 41; // % of its height

const TANGENT_PX = 16; // how far a node slides along its own arc while idle
const INWARD_PX = 5; // and toward the funnel while it does

/**
 * How much further out a signal sits while it is not yet possible.
 *
 * <p>Distance and stillness are the whole of how a blocked node reads — it is not dimmed, dashed or
 * disabled. It is a real control that opens a real explanation; it simply is not caught in the field
 * yet, so the field is not moving it. Small enough that the ring still reads as one ring.
 */
const BLOCKED_DISTANCE = 1.09;

const RADIANS = Math.PI / 180;

/**
 * Where signal {@code index} of {@code total} sits on the ring, and which way it drifts.
 *
 * <p>Evenly around it, clockwise from twelve o'clock — so visual order matches DOM order matches the
 * order the domain lists its items in, which is what lets tabbing follow the ring with no
 * `tabindex` anywhere. Nothing is nudged, excluded or clustered: an orbit with gaps carved out of it
 * stops being an orbit, so where a node and the drawing would collide the answer is to size the
 * drawing to fit the ring, never to bend the ring around the drawing.
 */
function station(index: number, total: number, available = true): CSSProperties {
  const degrees = total === 1 ? 0 : -90 + (index * 360) / total;
  const theta = degrees * RADIANS;
  const cos = Math.cos(theta);
  const sin = Math.sin(theta);
  const distance = available ? 1 : BLOCKED_DISTANCE;
  const rx = ORBIT_RX * distance;
  const ry = ORBIT_RY * distance;

  return {
    left: `${(50 + cos * rx).toFixed(3)}%`,
    top: `${(50 + sin * ry).toFixed(3)}%`,
    '--dx': `${(-sin * TANGENT_PX - cos * INWARD_PX).toFixed(2)}px`,
    '--dy': `${(cos * TANGENT_PX - sin * INWARD_PX).toFixed(2)}px`,
    // Where "in" is from here, so a consumed signal travels to the funnel rather than to a corner.
    '--tox': `${(-cos * rx).toFixed(3)}%`,
    '--toy': `${(-sin * ry).toFixed(3)}%`,
    '--i': index,
  } as CSSProperties;
}

const GOLDEN_ANGLE = 137.5;

/** Where a settled mote rests inside the core — a phyllotaxis spiral, so no two crowd each other. */
function mote(index: number): CSSProperties {
  const theta = index * GOLDEN_ANGLE * RADIANS;
  const radius = 3.5 + (index % 3) * 1.8;

  return {
    // Centred on the throat — where the cone actually closes, not on the middle of the box.
    left: `${(50 + Math.cos(theta) * radius).toFixed(3)}%`,
    top: `${(THROAT_CY + Math.sin(theta) * radius * 0.45).toFixed(3)}%`,
    '--i': index,
  } as CSSProperties;
}
