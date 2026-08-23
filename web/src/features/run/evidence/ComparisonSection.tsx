import { Text, Title } from '@mantine/core';
import type { ComparisonEvidence, MetricDelta } from '../../../api/run';
import { dominantDeltas, improvedDeltas, unclassifiedDeltas } from './comparisonDominant';
import shared from './shared.module.css';
import classes from './ComparisonSection.module.css';

const VERDICT_TONE: Record<string, string> = {
  REGRESSED: shared.fail,
  IMPROVED: shared.pass,
  UNCHANGED: shared.neutral,
};

/**
 * Whether anything changed since the last comparable run — led by the verdict and, when it is
 * `REGRESSED`, by which metric(s) actually crossed the noise threshold. `RegressionEvaluator` itself
 * only classifies the comparison as a whole; the dominant reason shown here is a presentation-layer
 * selection over `MetricDelta.isDegradation` (see `comparisonDominant.ts`), never a second regression
 * rule.
 */
export function ComparisonSection({
  comparison,
  previousId,
}: {
  comparison: ComparisonEvidence;
  previousId: string | null;
}) {
  const dominant = dominantDeltas(comparison);
  const improved = improvedDeltas(comparison);
  const unclassified = unclassifiedDeltas(comparison);

  return (
    <section>
      <Title order={2} size="h4" mb={4}>
        Compared with {comparison.baselineLabel}
      </Title>
      <Text size="xs" c="dimmed" mb="sm">
        {comparison.baselineFinishedAtDisplay}
        {previousId && (
          <>
            {' · '}
            <a href={`/runs/${previousId}`}>view run</a>
          </>
        )}
      </Text>

      {comparison.supportsVerdict ? (
        <>
          {comparison.verdictLabel && (
            <Title order={3} size="h4" className={VERDICT_TONE[comparison.verdictLabel] ?? ''} mb={4}>
              {comparison.verdictLabel}
            </Title>
          )}

          {dominant.length > 0 && (
            <div className={classes.deltaList}>
              {dominant.map((delta) => (
                <DeltaRow key={delta.metric} delta={delta} tone="fail" />
              ))}
            </div>
          )}

          {improved.length > 0 && (
            <details className={shared.disclosure} open={dominant.length === 0}>
              <summary>{dominant.length === 0 ? 'Improvements' : `Also improved (${improved.length})`}</summary>
              <div className={classes.deltaList}>
                {improved.map((delta) => (
                  <DeltaRow key={delta.metric} delta={delta} tone="pass" />
                ))}
              </div>
            </details>
          )}

          {unclassified.length > 0 && (
            <details className={shared.disclosure}>
              <summary>Unchanged or not comparable ({unclassified.length})</summary>
              <div className={classes.deltaList}>
                {unclassified.map((delta) => (
                  <DeltaRow key={delta.metric} delta={delta} tone="neutral" />
                ))}
              </div>
            </details>
          )}
        </>
      ) : (
        <Text size="sm" c="dimmed">
          {comparison.notComparableExplanation}
        </Text>
      )}

      {comparison.differences.length > 0 && (
        <ul className={shared.list}>
          {comparison.differences.map((difference) => (
            <li key={difference}>{difference}</li>
          ))}
        </ul>
      )}
    </section>
  );
}

function DeltaRow({ delta, tone }: { delta: MetricDelta; tone: 'fail' | 'pass' | 'neutral' }) {
  return (
    <div className={classes.deltaRow}>
      <span>{delta.metric}</span>
      <span className={shared.dim}>{delta.display}</span>
      <span className={shared[tone]}>{delta.percentChangeDisplay}</span>
    </div>
  );
}
