import { Tooltip } from '@mantine/core';
import type { ServiceCard } from '../api/home';
import { resolveCommand, type CommandAction } from '../lib/workbenchState';
import { InfoPopover } from './InfoPopover';
import classes from './CommandBar.module.css';

const COMMANDS: ReadonlyArray<{ glyph: string; label: string; action: CommandAction; hint: string }> = [
  { glyph: '◎', label: 'Find its limit', action: 'find-limit', hint: 'Breakpoint test — where it stops meeting its objectives' },
  { glyph: '◇', label: 'Validate capacity', action: 'validate-capacity', hint: 'Average-load test against its recorded conditions' },
  { glyph: '▥', label: 'Compare runs', action: 'compare-runs', hint: 'See its evidence and compare releases' },
  { glyph: '⌁', label: 'Use production traffic', action: 'production-traffic', hint: 'Ground the workload in what it actually receives' },
];

/**
 * The command surface a selected service reveals — SERVICE → INTENT → WORKSPACE. A service that
 * can't run a test yet doesn't get offered the four performance commands (they'd just bounce to
 * configuration): it gets one command, the one thing actually available to it right now.
 *
 * <p>Every command states what it will do for *this* service before you press it — "Run
 * breakpoint-ramp", "Set one up" — because four verbs that all lead to the same page is a menu
 * pretending to be a control surface. The sub-line is resolved from the service's own facts, so it
 * is a claim Vortex can stand behind rather than a promise the destination may not keep.
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
        {COMMANDS.map((command) => {
          const resolution = resolveCommand(service, command.action);

          const body = (
            <>
              <span className={classes.glyph} aria-hidden="true">
                {command.glyph}
              </span>
              <span className={classes.body}>
                <span className={classes.label}>{command.label}</span>
                <span className={classes.detail}>
                  {resolution.unavailableReason ?? resolution.detail}
                </span>
              </span>
            </>
          );

          // A disabled <button>, never an <a> without href: a hrefless anchor is neither focusable
          // nor announced as a control, which is a control that has silently stopped being one.
          // This stays out of the tab order while still announcing as currently unavailable — and
          // no Tooltip, since a disabled button fires no pointer events and the reason is already
          // on screen rather than hidden behind a hover.
          if (!resolution.enabled || !resolution.href) {
            return (
              <button
                key={command.action}
                type="button"
                disabled
                className={`${classes.command} ${classes.unavailable}`}
              >
                {body}
                <span className="visually-hidden"> — unavailable</span>
              </button>
            );
          }

          return (
            <Tooltip key={command.action} label={command.hint} openDelay={400} withArrow>
              <a className={classes.command} href={resolution.href}>
                {body}
              </a>
            </Tooltip>
          );
        })}
      </div>
    </div>
  );
}
