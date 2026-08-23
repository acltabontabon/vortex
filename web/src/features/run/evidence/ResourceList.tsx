import { Text } from '@mantine/core';
import type { ResourceSignal } from '../../../api/run';
import { UtilizationBar } from '../../../components/charts/UtilizationBar';
import shared from './shared.module.css';
import classes from './ResourcesSection.module.css';

/**
 * One resource's name, figure and utilisation bar. Shared by {@link ResourcesSection} (the system
 * under test) and {@link EvidenceProvenanceSection} (the load generator and its host) so the two
 * never drift into rendering the same kind of row differently.
 */
export function ResourceList({ signals }: { signals: ResourceSignal[] }) {
  return (
    <div className={shared.table}>
      {signals.map((signal) => (
        <div key={signal.id} className={classes.row}>
          <div className={classes.name}>
            <Text size="sm">{signal.name}</Text>
            <Text size="xs" c="dimmed">
              {signal.kindLabel}
            </Text>
          </div>
          <Text size="sm" className={classes.display}>
            {signal.display}
            {signal.limitDisplay && <span className={shared.dim}> / {signal.limitDisplay}</span>}
          </Text>
          <UtilizationBar fraction={signal.utilisationFraction} atLimit={signal.atItsLimit} />
          <Text size="xs" className={signal.atItsLimit ? shared.fail : shared.dim}>
            {signal.utilisationDisplay
              ? `${signal.utilisationDisplay} of limit${signal.atItsLimit ? ' — at limit' : ''}`
              : 'no limit published'}
          </Text>
        </div>
      ))}
    </div>
  );
}
