import { useSearchParams } from 'react-router-dom';
import { Alert, Button, Card, Skeleton, Stack, Text, Title } from '@mantine/core';
import { useCompareQuery, useComparisonAnalysisPanel } from '../../api/globalRuns';
import type { CompareSide } from '../../api/globalRuns';
import { AsyncPanel } from '../../components/AsyncPanel';
import { errorFallback } from '../../lib/queryFallback';
import classes from './ComparePage.module.css';
import analysisClasses from './RunAnalysisPanel.module.css';

/**
 * Two runs, side by side.
 *
 * <p>Comparison is permissive and regression evaluation is strict, and that separation carries into
 * the page: any two runs can be shown together, because an engineer looking at two dissimilar runs
 * is doing something legitimate. A formal verdict appears only when they tested the same
 * experiment — otherwise the percentage would measure the difference between two experiments rather
 * than a change in the service, and the page says so instead of hiding the numbers.
 */
export function ComparePage() {
  const [searchParams] = useSearchParams();
  const baseline = searchParams.get('baseline') ?? '';
  const candidate = searchParams.get('candidate') ?? '';

  const { data, isError } = useCompareQuery(baseline, candidate);
  const { status, start } = useComparisonAnalysisPanel(baseline, candidate);

  const error = errorFallback(isError, 'Could not load this comparison');
  if (error) return error;

  if (!data) return <Skeleton height={420} radius="md" />;

  return (
    <Stack gap="lg">
      <Title order={1} size="h2">
        Comparing two runs
      </Title>

      {!data.supportsRegressionVerdict ? (
        <Alert color="warn" title="These runs tested different experiments">
          <Text size="sm">{data.notComparableExplanation}</Text>
          {data.differences.length > 0 && (
            <ul className={classes.list}>
              {data.differences.map((difference) => (
                <li key={difference}>{difference}</li>
              ))}
            </ul>
          )}
        </Alert>
      ) : (
        <Alert color="live" title={data.verdictLabel ?? undefined}>
          <Text size="sm">
            Both runs applied the same workload to the same target under the same objectives, so a
            difference between them is attributable to the service rather than to the test.{' '}
            {data.verdictDescription}
          </Text>
        </Alert>
      )}

      {(data.baselineReleaseMissing || data.candidateReleaseMissing) && (
        <Alert color="neutral" title="At least one of these runs did not record its release">
          <Text size="sm">
            The measurements are still valid, but nothing here says which build produced them. Set{' '}
            <code>service.version</code> in <code>vortex.yaml</code>, or pass{' '}
            <code>--service-version</code> when running from a pipeline, so a future comparison can
            name what changed.
          </Text>
        </Alert>
      )}

      <Card withBorder radius="md">
        <Title order={2} size="h4" mb="sm">
          Differences
        </Title>
        {data.deltas.length > 0 ? (
          <div className={classes.table}>
            <div className={classes.head}>
              <span>Measurement</span>
              <span>Baseline → candidate</span>
              <span>Change</span>
            </div>
            {data.deltas.map((delta) => (
              <div key={delta.metric} className={classes.row}>
                <span>{delta.metric}</span>
                <span>{delta.display}</span>
                <span>{delta.percentChangeDisplay}</span>
              </div>
            ))}
          </div>
        ) : (
          <Text size="sm" c="dimmed">
            Neither run recorded measurements to compare.
          </Text>
        )}
        {!data.supportsRegressionVerdict && (
          <Text size="xs" c="dimmed" mt="sm">
            These are observed differences, not a conclusion. Vortex will not turn them into a
            regression verdict, because the two runs did not test the same experiment.
          </Text>
        )}
      </Card>

      <div className={classes.split}>
        <Side title="Baseline" side={data.baseline} />
        <Side title="Candidate" side={data.candidate} />
      </div>

      <section>
        <Title order={2} size="h4" mb="sm">
          AI interpretation
        </Title>

        {status.data && (
          <AsyncPanel
            title="Interpreting"
            isRunning={status.data.analysing}
            runningMessage="Interpreting. The differences above are already final — this only adds interpretation."
            availability={status.data.availability}
            hasResult={status.data.latest !== null}
          >
            {status.data.latest && (
              <Card withBorder radius="md">
                <Text size="sm">{status.data.latest.conclusion}</Text>
                {status.data.latest.findings.length > 0 && (
                  <Stack gap={4} mt="sm">
                    {status.data.latest.findings.map((finding, i) => (
                      <Text key={i} size="sm">
                        <span className={analysisClasses.typeTag}>{finding.typeLabel}</span>{' '}
                        {finding.statement}
                        <span className={analysisClasses.dim}> ({finding.confidenceLabel})</span>
                      </Text>
                    ))}
                  </Stack>
                )}
                {status.data.latest.missingTelemetry.length > 0 && (
                  <Stack gap={2} mt="sm">
                    <Text size="xs" fw={600} c="dimmed">
                      Not measured on one or both sides
                    </Text>
                    {status.data.latest.missingTelemetry.map((missing, i) => (
                      <Text key={i} size="xs" c="dimmed">
                        <strong>{missing.what}</strong> — {missing.whyItMatters}
                      </Text>
                    ))}
                  </Stack>
                )}
                {status.data.latest.provenanceDescribe && (
                  <Text size="xs" c="dimmed" mt="sm">
                    {status.data.latest.provenanceDescribe}
                  </Text>
                )}
              </Card>
            )}
          </AsyncPanel>
        )}

        {status.data && !status.data.analysing && status.data.latest === null && status.data.availability.available && (
          <Button onClick={() => start.mutate()} loading={start.isPending} variant="light">
            Explain this comparison
          </Button>
        )}
      </section>
    </Stack>
  );
}

function Side({ title, side }: { title: string; side: CompareSide }) {
  return (
    <Card withBorder radius="md">
      <Title order={3} size="h5" mb="sm">
        {title}
      </Title>
      <dl className={classes.facts}>
        <dt>Run</dt>
        <dd>
          <a href={`/runs/${side.executionId}`}>{side.workloadName}</a>
        </dd>
        <dt>Release</dt>
        <dd>{side.serviceVersion ?? '—'}</dd>
        <dt>Environment</dt>
        <dd>{side.environmentName}</dd>
        <dt>When</dt>
        <dd>{side.requestedAtDisplay}</dd>
      </dl>
    </Card>
  );
}
