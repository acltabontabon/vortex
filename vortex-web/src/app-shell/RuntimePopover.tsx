import type { RuntimeSummary, RuntimeCheck } from './api';
import classes from './StatusBar.module.css';

function CheckRow({ check }: { check: RuntimeCheck }) {
  const markClass = check.ok ? 'ok' : check.required ? 'notOk' : 'absent';
  return (
    <div className={classes.check}>
      <span className={`${classes.mark} ${classes[markClass]}`} aria-hidden="true">
        {check.mark}
      </span>
      <div>
        <div className={classes.checkName}>
          {check.name}
          <span className="visually-hidden">{check.ok ? 'available' : 'unavailable'}</span>
        </div>
        <div className={classes.checkDetail}>{check.detail}</div>
        {!check.ok && check.remedy && <p className={classes.checkRemedy}>{check.remedy}</p>}
      </div>
    </div>
  );
}

export function RuntimePopover({ summary }: { summary: RuntimeSummary }) {
  const required = summary.checks.filter((c) => c.required);
  const optional = summary.checks.filter((c) => !c.required);

  return (
    <div className={classes.popover}>
      <div className={classes.heading}>Required to run a test</div>
      {required.map((check) => (
        <CheckRow key={check.name} check={check} />
      ))}

      <div className={classes.heading}>Optional</div>
      {optional.map((check) => (
        <CheckRow key={check.name} check={check} />
      ))}

      <div className={classes.links}>
        <a href="/runtime">Runtime detail</a>
        <a href="/settings">Settings</a>
      </div>
    </div>
  );
}
