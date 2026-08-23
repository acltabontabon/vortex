import type { TimelineStageRow } from '../../api/run';
import classes from './CapacityCurve.module.css';

/**
 * The level-by-level story a ramping or stepped run tells: which levels held, and where it stopped.
 *
 * <p>Extends {@code StageLadder}'s own dot-per-stage table rather than introducing a second way to
 * draw the same rows. The one addition is the "first objective violation" callout — found by scanning
 * for the first `resultKind === 'violated'` row in an already-chronological list, not by matching a
 * timestamp: `TimelineStageRow` carries no per-stage instant to match against, and the stages already
 * arrive in the order they ran, so "first violated row" and "first violated instant" are the same
 * fact read two ways.
 *
 * <p>Draws only the resource signals a stage's own {@code signals} already carries (already-formatted
 * strings like "CPU 82%") behind a per-row disclosure — it never infers a CPU or memory saturation
 * marker that isn't already in the data.
 */
export function CapacityCurve({ stages }: { stages: TimelineStageRow[] }) {
  if (stages.length < 2) return null;

  const firstViolatedIndex = stages.findIndex((stage) => stage.resultKind === 'violated');

  return (
    <ol className={classes.curve}>
      {stages.map((stage, index) => {
        const violated = stage.resultKind === 'violated';
        const isFirstFailure = index === firstViolatedIndex;
        return (
          <li key={index} className={classes.row}>
            <span
              className={`${classes.dot} ${violated ? classes.violated : classes.met}`}
              aria-hidden="true"
            />
            <span className={classes.level}>{stage.levelDisplay}</span>
            <span className={classes.metric}>{stage.achievedDisplay}</span>
            <span className={classes.metric}>{stage.p95Display}</span>
            <span className={classes.metric}>{stage.errorRateDisplay}</span>
            <span className={violated ? classes.violatedResult : classes.metResult}>
              {violated ? stage.violatedThresholds.join(', ') || 'violated' : 'met'}
            </span>
            {isFirstFailure && <span className={classes.callout}>← first objective violation</span>}
            {stage.signals.length > 0 && (
              <details className={classes.signals}>
                <summary>{stage.signals.length === 1 ? '1 signal' : `${stage.signals.length} signals`}</summary>
                <ul>
                  {stage.signals.map((signal) => (
                    <li key={signal}>{signal}</li>
                  ))}
                </ul>
              </details>
            )}
          </li>
        );
      })}
    </ol>
  );
}
