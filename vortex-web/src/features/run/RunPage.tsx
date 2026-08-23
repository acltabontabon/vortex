import { useParams } from 'react-router-dom';
import { Alert, Button, Group, Skeleton, Stack, Text, Title } from '@mantine/core';
import { useCancelRunMutation, useRunQuery } from '../../api/run';
import { useRunProgress } from '../../api/runs';
import { RunLiveView } from './RunLiveView';
import { RunEvidenceView } from './RunEvidenceView';
import { RunAnalysisPanel } from './RunAnalysisPanel';
import { errorFallback } from '../../lib/queryFallback';

/**
 * One run, from in-flight to evidence.
 *
 * <p>The live view and the result view were always the same route in Thymeleaf
 * (`ExecutionController.result()` branched on terminal state server-side) — kept here rather than
 * split into two routes, so the moment a run finishes is a state change, not a navigation. Not
 * nested under `/services/:id`: a run outlives edits to the service it tested, and re-fetching the
 * service header on every progress tick would be wasted work for data this page never shows.
 */
export function RunPage() {
  const { id = '' } = useParams();
  const runQuery = useRunQuery(id);
  const cancelMutation = useCancelRunMutation(id);

  const run = runQuery.data;
  const progress = useRunProgress(id, {
    enabled: !!run && !run.terminal,
    initialProgress: run?.progress ?? null,
    onFinished: () => runQuery.refetch(),
  });

  const error = errorFallback(runQuery.isError, 'Could not load this run',
      `/api/runs/${id} did not respond. Reload the page to try again.`);
  if (error) return error;

  if (!run) return <Skeleton height={420} radius="md" />;

  const runAgainHref = `/services/${run.plan.projectId}/run?workload=${encodeURIComponent(
    run.plan.workloadName
  )}&environment=${encodeURIComponent(run.plan.environmentName)}`;

  if (!run.terminal) {
    return (
      <RunLiveView
        run={run}
        progress={progress}
        onCancel={() => cancelMutation.mutate()}
        cancelPending={cancelMutation.isPending}
      />
    );
  }

  if (run.cancelled || run.failed) {
    return (
      <Stack gap="md" maw={640}>
        <Title order={1} size="h2">
          {run.plan.workloadName}
        </Title>
        <Alert color={run.cancelled ? 'neutral' : 'fail'} title={run.failureLabel ?? 'Cancelled'}>
          <Text size="sm">{run.failureGuidance}</Text>
          {run.failureDetail && (
            <Text size="xs" c="dimmed" mt="xs" style={{ whiteSpace: 'pre-line' }}>
              {run.failureDetail}
            </Text>
          )}
        </Alert>
        <Group>
          <Button component="a" href={runAgainHref}>
            Run again
          </Button>
          <Button component="a" href={`/services/${run.plan.projectId}`} variant="default">
            Back to service
          </Button>
        </Group>
      </Stack>
    );
  }

  if (!run.evidence) return <Skeleton height={420} radius="md" />;

  return (
    <Stack gap="xl">
      <RunEvidenceView
        evidence={run.evidence}
        serviceId={run.plan.projectId}
        executionId={id}
        runAgainHref={runAgainHref}
      />

      <RunAnalysisPanel executionId={id} />
    </Stack>
  );
}
