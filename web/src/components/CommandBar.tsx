import { Tooltip } from '@mantine/core';
import type { ServiceCard } from '../api/home';
import { commandHref } from '../lib/workbenchState';
import { InfoPopover } from './InfoPopover';
import classes from './CommandBar.module.css';

const COMMANDS = [
  { glyph: '◎', label: 'Find its limit', action: 'find-limit', hint: 'Breakpoint test — where it stops meeting its objectives' },
  { glyph: '◇', label: 'Validate capacity', action: 'validate-capacity', hint: 'Average-load test against its recorded conditions' },
  { glyph: '▥', label: 'Compare runs', action: 'compare-runs', hint: 'See its evidence and compare releases' },
  { glyph: '⌁', label: 'Use production traffic', action: 'production-traffic', hint: 'Ground the workload in what it actually receives' },
] as const;

/**
 * The command surface a selected service reveals — SERVICE → INTENT → WORKSPACE. A service that
 * can't run a test yet doesn't get offered the four performance commands (they'd just bounce to
 * configuration): it gets one command, the one thing actually available to it right now.
 */
export function CommandBar({ service }: { service: ServiceCard }) {
  if (!service.canRun) {
    return (
      <div className={classes.wrap}>
        <div className={classes.header}>
          <span className={classes.serviceTag}>{service.name}</span>
          <span className={classes.prompt}>
            needs {service.blockers.length} setup item{service.blockers.length === 1 ? '' : 's'}
          </span>
          {service.blockers.length > 0 && (
            <InfoPopover label="What's missing?">
              <ul className={classes.blockerList}>
                {service.blockers.map((blocker) => (
                  <li key={blocker}>{blocker}</li>
                ))}
              </ul>
            </InfoPopover>
          )}
        </div>
        <div className={classes.row}>
          <a className={`${classes.command} ${classes.solo}`} href={`/services/${service.id}/configuration`}>
            <span className={classes.glyph} aria-hidden="true">
              →
            </span>
            Continue setup
          </a>
        </div>
      </div>
    );
  }

  return (
    <div className={classes.wrap}>
      <div className={classes.header}>
        <span className={classes.serviceTag}>{service.name}</span>
        <span className={classes.prompt}>What do you want to test?</span>
      </div>
      <div className={classes.row}>
        {COMMANDS.map((command) => (
          <Tooltip key={command.action} label={command.hint} openDelay={400} withArrow>
            <a className={classes.command} href={commandHref(service, command.action)}>
              <span className={classes.glyph} aria-hidden="true">
                {command.glyph}
              </span>
              {command.label}
            </a>
          </Tooltip>
        ))}
      </div>
    </div>
  );
}
