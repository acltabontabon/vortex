import { Text, Title } from '@mantine/core';
import type { AcceptanceEvidence } from '../../../api/run';
import { VerdictBadge } from '../../../components/VerdictBadge';
import { ObjectiveBar } from '../../../components/charts/ObjectiveBar';
import shared from './shared.module.css';
import classes from './ObjectivesPanel.module.css';

/**
 * Every objective, as one compact row each: verdict mark, what was measured, and a bullet chart
 * showing how far it sits from its own limit — replacing the old page's larger card-per-objective
 * layout. `EvidenceIds` never surface here; a row's own `note` (why it could not be evaluated, when
 * applicable) is the only elaboration offered inline.
 */
export function ObjectivesPanel({ acceptance }: { acceptance: AcceptanceEvidence }) {
  return (
    <section>
      <Title order={2} size="h4" mb="sm">
        Objectives
      </Title>
      {!acceptance.hasObjectives ? (
        <Text size="sm" c="dimmed">
          {acceptance.absenceExplanation}
        </Text>
      ) : (
        <div className={shared.table}>
          {acceptance.results.map((result) => (
            <div key={result.describe} className={classes.row}>
              <VerdictBadge
                verdict={result.verdict as 'PASS' | 'FAIL' | 'NOT_EVALUATED'}
                label={result.verdictLabel}
                subtleText
              />
              <div className={classes.describe}>
                <Text size="sm">{result.describe}</Text>
                {result.note && (
                  <Text size="xs" c="dimmed">
                    {result.note}
                  </Text>
                )}
              </div>
              <Text size="sm" fw={600} className={classes.observed}>
                {result.observed || '—'}
              </Text>
              <ObjectiveBar position={result.observedPosition} verdict={result.verdict as 'PASS' | 'FAIL' | 'NOT_EVALUATED'} />
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
