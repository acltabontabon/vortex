import type { KeyboardEvent } from 'react';
import type { ServiceCard } from '../api/home';
import { STATUS, stateOf, primaryAction, workloadSummary } from '../lib/workbenchState';
import { ServiceBadge } from './ServiceBadge';
import classes from './ShelfCard.module.css';

interface ShelfCardProps {
  service: ServiceCard;
  selected: boolean;
  onSelect: () => void;
  cardRef?: (el: HTMLElement | null) => void;
}

export function ShelfCard({ service, selected, onSelect, cardRef }: ShelfCardProps) {
  const state = stateOf(service);
  const status = STATUS[state];
  const primary = primaryAction(service, state);
  const summary = workloadSummary(service);
  const testedCapacity = service.rangeMarkers.find((m) => m.kind === 'TESTED_CAPACITY');
  const productionPeak = service.rangeMarkers.find((m) => m.kind === 'PRODUCTION');

  const handleKeyDown = (e: KeyboardEvent<HTMLElement>) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      onSelect();
    }
  };

  return (
    <article
      ref={cardRef}
      className={`${classes.card} ${selected ? classes.selected : ''}`}
      role="button"
      aria-pressed={selected}
      tabIndex={0}
      onClick={onSelect}
      onKeyDown={handleKeyDown}
    >
      <div className={classes.header}>
        <ServiceBadge id={service.id} name={service.name} />
        <span className={classes.name} title={service.name}>
          {service.name}
        </span>
      </div>

      <div className={classes.statusLine}>
        <span className={classes.dot} style={{ background: status.color }} />
        <span className={classes.statusLabel}>{status.label}</span>
        {(state === 'pass' || state === 'fail' || state === 'unevaluated') && service.latestVerdict && (
          <span className={classes.statusWhen}>· {service.latestVerdict.relativeTime}</span>
        )}
        {state === 'running' && service.runningRun && (
          <span className={classes.statusWhen}>· {service.runningRun.testTypeLabel}</span>
        )}
      </div>

      {(state === 'pass' || state === 'fail' || state === 'unevaluated') && (
        <div className={classes.evidence}>
          {testedCapacity && (
            <div>
              <div className={classes.metric}>{testedCapacity.displayWithUnit}</div>
              <div className={classes.caption}>{testedCapacity.label}</div>
            </div>
          )}
          {service.latestVerdict?.p95 && <div className={classes.fact}>p95 {service.latestVerdict.p95}</div>}
          {productionPeak && (
            <div className={classes.factMuted}>
              Production peak {productionPeak.displayWithUnit}
              {service.headroomDisplay && <> · {service.headroomDisplay} tested headroom</>}
            </div>
          )}
        </div>
      )}

      {(state === 'setup' || state === 'ready') && (
        <div className={classes.evidence}>
          {summary && <div className={classes.factMuted}>{summary}</div>}
          {state === 'setup' && (
            <div className={classes.fact}>
              {service.blockers.length} required item{service.blockers.length === 1 ? '' : 's'} remaining
            </div>
          )}
          {service.nextStepText && <div className={classes.factMuted}>Next: {service.nextStepText}</div>}
        </div>
      )}

      <a
        className={classes.action}
        href={primary.href}
        onClick={(e) => e.stopPropagation()}
      >
        {primary.label} →
      </a>
    </article>
  );
}
