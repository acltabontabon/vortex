import { Button, Card, Stack, Text, Title } from '@mantine/core';
import { useRunAnalysisPanel } from '../../api/run';
import type { Analysis } from '../../api/run';
import { AsyncPanel } from '../../components/AsyncPanel';
import classes from './RunAnalysisPanel.module.css';

/**
 * AI interpretation of a completed run — exploratory, and clearly separated from the measurements
 * above it. The measurements are already final by the time this panel exists; this only adds a
 * second reading of them, one an analyst could get wrong, so the panel that carries it looks
 * nothing like the evidence sections it sits beside.
 */
export function RunAnalysisPanel({ executionId }: { executionId: string }) {
  const { status, start } = useRunAnalysisPanel(executionId);

  if (!status.data) return null;
  const panel = status.data;

  return (
    <section>
      <Title order={2} size="h4" mb="sm">
        AI interpretation
      </Title>

      <AsyncPanel
        title="Analysing"
        isRunning={panel.analysing}
        runningMessage="Analysing. The measurements above are already final — this only adds interpretation."
        availability={panel.availability}
        hasResult={panel.latest !== null}
      >
        {panel.latest && <AnalysisView analysis={panel.latest} />}
      </AsyncPanel>

      {!panel.analysing && panel.latest === null && panel.availability.available && (
        <Button onClick={() => start.mutate()} loading={start.isPending} variant="light">
          Analyse with AI
        </Button>
      )}

      {panel.earlier.length > 0 && (
        <details className={classes.disclosure}>
          <summary>Earlier analyses ({panel.earlierCount})</summary>
          <Stack gap="md" mt="sm">
            {panel.earlier.map((analysis, i) => (
              <AnalysisView key={i} analysis={analysis} />
            ))}
          </Stack>
        </details>
      )}
    </section>
  );
}

function AnalysisView({ analysis }: { analysis: Analysis }) {
  return (
    <Card withBorder radius="md">
      <Text size="sm">{analysis.conclusion}</Text>

      {analysis.findings.length > 0 && (
        <Stack gap={4} mt="sm">
          {analysis.findings.map((finding, i) => (
            <Text key={i} size="sm">
              <span className={classes.typeTag}>{finding.typeLabel}</span> {finding.statement}
              <span className={classes.dim}> ({finding.confidenceLabel})</span>
            </Text>
          ))}
        </Stack>
      )}

      {analysis.recommendations.length > 0 && (
        <Stack gap={4} mt="sm">
          <Text size="xs" fw={600} c="dimmed">
            Recommended
          </Text>
          {analysis.recommendations.map((rec, i) => (
            <Text key={i} size="sm">
              {rec.action}
              <span className={classes.dim}> — {rec.rationale}</span>
            </Text>
          ))}
        </Stack>
      )}

      {analysis.nextTest && (
        <Stack gap={2} mt="sm">
          <Text size="xs" fw={600} c="dimmed">
            Suggested next test
          </Text>
          <Text size="sm">{analysis.nextTest.action}</Text>
          <Text size="xs" c="dimmed">
            {analysis.nextTest.rationale} Would distinguish: {analysis.nextTest.wouldDistinguish}
          </Text>
        </Stack>
      )}

      {analysis.missingTelemetry.length > 0 && (
        <Stack gap={2} mt="sm">
          <Text size="xs" fw={600} c="dimmed">
            Would help next time
          </Text>
          {analysis.missingTelemetry.map((missing, i) => (
            <Text key={i} size="xs" c="dimmed">
              <strong>{missing.what}</strong> — {missing.whyItMatters}
            </Text>
          ))}
        </Stack>
      )}

      {analysis.provenanceDescribe && (
        <Text size="xs" c="dimmed" mt="sm">
          {analysis.provenanceDescribe}
        </Text>
      )}
    </Card>
  );
}
