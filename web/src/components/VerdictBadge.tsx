import type { Verdict } from '../api/workspace';
import { VERDICT_COLOR } from '../lib/testState';
import classes from './VerdictBadge.module.css';

/**
 * A run's verdict, as a mark and a word.
 *
 * <p>Never colour alone — the word is always spelled out beside it. And never the brand green:
 * solid brand fill means "do this", and a verdict is not an action. `Not evaluated` draws neutral,
 * because an objective that was never checked has not been met and must not be dressed as either
 * outcome.
 *
 * <p>`subtleText` keeps the colour on the dot only and lets the word take the page's normal text
 * colour — for a list of several verdicts in a row (recent activity, a test's own footer), where a
 * column of coloured words reads as louder than the state actually warrants. Off by default so every
 * existing call site (the Runs tab, the Evidence tab, a single verdict standing alone) is unchanged.
 */
export function VerdictBadge({
  verdict,
  label,
  size = 'sm',
  subtleText = false,
}: {
  verdict: Verdict;
  label: string;
  size?: 'sm' | 'lg';
  subtleText?: boolean;
}) {
  return (
    <span className={`${classes.badge} ${size === 'lg' ? classes.lg : ''}`}>
      <span
        className={classes.dot}
        style={{ background: VERDICT_COLOR[verdict] }}
        aria-hidden="true"
      />
      <span
        className={classes.label}
        style={subtleText ? undefined : { color: VERDICT_COLOR[verdict] }}
      >
        {label}
      </span>
    </span>
  );
}
