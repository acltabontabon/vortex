import type { TimelineStageRow } from '../../api/run';
import classes from './StageLadder.module.css';

/**
 * Stress's own distinguishing addition to the wide range figure — Breakpoint stops at the boundary
 * (see `CapacityRangeFigure`'s `emphasize` prop), but Stress's question is how the service behaves
 * as pressure moves through normal, peak, above peak and on toward saturation, and that is a
 * chronological story the two edge marks alone can't tell.
 *
 * <p>Deliberately a table of the stages Vortex actually measured, in the order they ran — not a
 * shaded gradient across the range figure. `CapacityRange`'s own design refuses that (see its doc
 * comment): there is no rule anywhere in Vortex that decides when a service is "approaching" its
 * limit, so a gradient would render an invented conclusion more persuasively than any one of these
 * rows, each of which is a real measurement.
 */
export function StageLadder({ stages }: { stages: TimelineStageRow[] }) {
  if (stages.length === 0) return null;

  return (
    <table className={classes.ladder}>
      <thead>
        <tr>
          <th></th>
          <th>Level</th>
          <th>Achieved</th>
          <th>p95</th>
          <th>Errors</th>
        </tr>
      </thead>
      <tbody>
        {stages.map((stage, index) => (
          <tr key={index}>
            <td>
              <span
                className={`${classes.dot} ${stage.resultKind === 'violated' ? classes.violated : classes.met}`}
                aria-label={stage.resultKind === 'violated' ? 'objective violated' : 'objective met'}
              />
            </td>
            <td>{stage.levelDisplay}</td>
            <td>{stage.achievedDisplay}</td>
            <td>{stage.p95Display}</td>
            <td>{stage.errorRateDisplay}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
