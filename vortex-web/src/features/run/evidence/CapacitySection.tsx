import { Alert, Text, Title } from '@mantine/core';
import type { Capacity, TimelineStageRow } from '../../../api/run';
import { Fact, Facts } from '../../../components/Fact';
import { Unknown } from '../../../components/Unknown';
import { CapacityCurve } from '../../../components/charts/CapacityCurve';
import shared from './shared.module.css';
import classes from './CapacitySection.module.css';

const OUTCOME_MARK: Record<string, string> = { MET: '✓', NOT_MET: '✕', NOT_EVALUATED: '○' };
const OUTCOME_TONE: Record<string, string> = { MET: shared.pass, NOT_MET: shared.fail, NOT_EVALUATED: shared.neutral };

/**
 * Capacity, as a claim rather than a wall of prose: a headline figure or its refusal, the highest
 * level actually tested (explicitly not the same claim), and the five conditions that decide between
 * them — reasoning that used to be several paragraphs is now a five-line checklist, with the full
 * statements available on request rather than always on screen.
 */
export function CapacitySection({
  capacity,
  stages,
  showCurve,
}: {
  capacity: Capacity;
  stages: TimelineStageRow[];
  showCurve: boolean;
}) {
  return (
    <section>
      <Title order={2} size="h4" mb="sm">
        Capacity
      </Title>

      {capacity.sustainableDisplay ? (
        <div className={classes.headline}>
          <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
            Sustainable capacity
          </Text>
          <Title order={2} size="h2" className={shared.pass}>
            {capacity.sustainableDisplay}
          </Title>
          <Text size="xs" c="dimmed">
            Evidence strength: {capacity.strengthLabel}
          </Text>
        </div>
      ) : (
        <Alert color="neutral" title="Not established" mb="sm">
          {capacity.refusal}
        </Alert>
      )}

      <Facts>
        {capacity.highestPassing && (
          <Fact
            label="Highest level that passed"
            note="Observed passing level — not a capacity claim."
          >
            {capacity.highestPassing}
          </Fact>
        )}
        <Fact label="Headroom">
          {capacity.headroomDisplay || (
            <Unknown compact what="Not stated" reason={capacity.headroomRefusal} />
          )}
        </Fact>
      </Facts>

      {capacity.conditions.length > 0 && (
        <div className={classes.checklist}>
          {capacity.conditions.map((condition) => (
            <div key={condition.condition} className={classes.checkRow}>
              <span className={`${classes.mark} ${OUTCOME_TONE[condition.outcome] ?? ''}`}>
                {OUTCOME_MARK[condition.outcome] ?? '?'}
              </span>
              <Text size="sm">{condition.label}</Text>
            </div>
          ))}
        </div>
      )}

      {!capacity.sustainableDisplay && capacity.conditions.length > 0 && (
        <details className={shared.disclosure}>
          <summary>Why wasn't capacity established?</summary>
          <ul className={shared.list}>
            {capacity.conditions
              .filter((c) => c.outcome !== 'MET')
              .map((c) => (
                <li key={c.condition}>{c.statement}</li>
              ))}
          </ul>
        </details>
      )}

      {showCurve && stages.length > 1 && (
        <div className={classes.curve}>
          <Text size="xs" c="dimmed" mb={4}>
            Level progression
          </Text>
          <CapacityCurve stages={stages} />
        </div>
      )}

      <div className={classes.limits}>
        <Text size="sm" c={capacity.noLimitEstablished ? 'dimmed' : undefined}>
          {capacity.firstLimit}
        </Text>
        {capacity.limits.length > 0 && (
          <details className={shared.disclosure}>
            <summary>Limits considered</summary>
            <table className={classes.limitsTable}>
              <tbody>
                {capacity.limits.map((limit) => (
                  <tr key={limit.kind}>
                    <td>{limit.label}</td>
                    <td>{limit.level || <span className={shared.dim}>not established</span>}</td>
                    <td className={shared.dim}>{limit.describe}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </details>
        )}
      </div>
    </section>
  );
}
