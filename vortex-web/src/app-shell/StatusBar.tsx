import { Popover } from '@mantine/core';
import { useRuntimeQuery } from './api';
import { RuntimePopover } from './RuntimePopover';
import classes from './StatusBar.module.css';

const GLANCE_NAMES = new Set(['Load generator', 'Docker', 'Local AI']);

function glanceLabel(name: string): string {
  if (name === 'Load generator') return 'k6';
  if (name === 'Local AI') return 'AI';
  return name;
}

/**
 * Supporting information about the machine, not a primary navigation destination — the same
 * "Runtime 4/5" idea that used to live in the top bar, now read the way a desktop app's status bar
 * reads: quiet, always there, one click from the full breakdown.
 */
export function StatusBar() {
  const { data: summary } = useRuntimeQuery();
  if (!summary) return null;

  const glance = summary.checks.filter((c) => GLANCE_NAMES.has(c.name));

  return (
    <footer className={classes.bar}>
      <Popover width={320} position="top-start" withArrow shadow="md">
        <Popover.Target>
          <button type="button" className={classes.trigger}>
            <span
              className={`${classes.dot} ${summary.requirementsMet ? '' : classes.problem}`}
              aria-hidden="true"
            />
            {summary.requirementsMet ? 'Runtime ready' : 'Runtime needs attention'}
          </button>
        </Popover.Target>
        <Popover.Dropdown>
          <RuntimePopover summary={summary} />
        </Popover.Dropdown>
      </Popover>

      <div className={classes.glance}>
        {glance.map((check) => (
          <span
            key={check.name}
            className={classes.glanceCheck}
            title={`${check.name} · ${check.detail}`}
          >
            {glanceLabel(check.name)}
            <span
              className={`${classes.mark} ${classes[check.ok ? 'ok' : check.required ? 'notOk' : 'absent']}`}
              aria-hidden="true"
            >
              {check.ok ? '✓' : check.required ? '✗' : '–'}
            </span>
          </span>
        ))}
      </div>
    </footer>
  );
}
