import classes from './UtilizationBar.module.css';

/**
 * How much of a resource's own limit was used, as a compact fill bar.
 *
 * <p>`fraction` is the same number `utilisationDisplay` formats — never re-derived from that string.
 * Renders nothing when `fraction` is null: a resource with no published limit has no fraction to draw,
 * and an empty bar in its place would read as "measured, and clear of its limit" rather than "no
 * limit is known here at all" (see `ResourceSignal.limitDisplay`'s own doc comment on that
 * distinction).
 */
export function UtilizationBar({
  fraction,
  atLimit,
}: {
  fraction: number | null;
  atLimit: boolean;
}) {
  if (fraction === null) return null;

  const percent = Math.min(Math.max(fraction, 0), 1) * 100;
  const tone = atLimit ? classes.atLimit : fraction >= 0.7 ? classes.high : classes.normal;

  return (
    <span className={classes.track} role="img" aria-label={`${Math.round(fraction * 100)}% of limit`}>
      <span className={`${classes.fill} ${tone}`} style={{ width: `${percent}%` }} />
      {fraction > 1 && <span className={classes.overflowMark} aria-hidden="true" />}
    </span>
  );
}
