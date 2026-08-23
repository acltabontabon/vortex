import classes from './ObjectiveBar.module.css';

/**
 * One objective, as a bullet chart: how far the observed value sits from its own limit, at a glance.
 *
 * <p>`position` is `AcceptanceResult.observedPosition` — a fraction of the threshold the domain
 * already computed (1.0 sits exactly at the limit). This component only draws it; it never re-parses
 * `observed` or `describe` to guess where the mark belongs, the same discipline `CapacityRangeFigure`
 * already follows for capacity marks. Renders nothing when `position` is null — an objective whose
 * measurement was unavailable has no mark to place, and a bar drawn at a guessed position would claim
 * more than the run measured.
 *
 * <p>Visual only: the observed value and the limit are printed by the caller (see
 * {@code ObjectivesPanel}), the same "figures beside the mark, never only in the drawing" rule
 * `CapacityRangeFigure` follows.
 */
export function ObjectiveBar({
  position,
  verdict,
}: {
  position: number | null;
  verdict: 'PASS' | 'FAIL' | 'NOT_EVALUATED';
}) {
  if (position === null) return null;

  const width = 96;
  const height = 14;
  const y = height / 2;
  // The limit itself always sits at the same place — 1.0 — so a bar's own width never has to change
  // to fit whatever crossed it; only the mark and the fill up to it move.
  const limitX = width;
  const clamped = Math.min(position, 1.18);
  const markX = clamped * width;
  const overflow = position > 1.18;
  const tone = verdict === 'FAIL' ? classes.fail : verdict === 'PASS' ? classes.pass : classes.neutral;

  return (
    <svg
      className={classes.bar}
      width={width + 10}
      height={height}
      viewBox={`0 0 ${width + 10} ${height}`}
      role="img"
      aria-label={verdict === 'FAIL' ? 'over the objective' : 'within the objective'}
    >
      <line className={classes.track} x1={0} y1={y} x2={width} y2={y} />
      <line
        className={`${classes.fill} ${tone}`}
        x1={0}
        y1={y}
        x2={Math.min(markX, width)}
        y2={y}
      />
      <line className={classes.limitTick} x1={limitX} y1={y - 5} x2={limitX} y2={y + 5} />
      <circle className={`${classes.mark} ${tone}`} cx={markX} cy={y} r={3} />
      {overflow && (
        <path
          className={`${classes.overflow} ${tone}`}
          d={`M${width + 4} ${y - 3} l4 3 l-4 3 z`}
        />
      )}
    </svg>
  );
}
