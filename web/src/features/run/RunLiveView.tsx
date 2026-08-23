import { Stack, Text, Title } from '@mantine/core';
import { LiveExecutionPanel } from '../../components/LiveExecutionPanel';
import type { Run } from '../../api/run';
import type { RunProgress } from '../../api/runs';

/**
 * A run, still in flight — the standalone-page counterpart to the service row's own
 * {@link ../service/RunningTestPanel.RunningTestPanel}, sharing the exact same
 * {@link LiveExecutionPanel} so the two never drift into two different vocabularies for the same
 * thing. The heading names the workload, matching every other state this page can be in
 * (`RunPage`'s failed/cancelled branch does the same); the live stage itself is the panel's own
 * header line; stating it twice at this size would say the same thing two ways in the same glance.
 */
export function RunLiveView({
  run,
  progress,
  onCancel,
  cancelPending,
}: {
  run: Run;
  progress: RunProgress | null;
  onCancel: () => void;
  cancelPending: boolean;
}) {
  return (
    <Stack gap="lg" maw={640}>
      <div>
        <Text size="sm" c="dimmed">
          {run.plan.testTypeLabel} · {run.plan.workloadName} against {run.plan.environmentName}
        </Text>
        <Title order={1} size="h2">
          {run.plan.workloadName}
        </Title>
      </div>

      <LiveExecutionPanel
        density="full"
        state={progress?.state}
        stage={progress?.stage ?? run.stateLabel}
        elapsed={progress?.elapsed ?? '00:00'}
        percent={progress?.percent ?? 0}
        targetRate={progress?.targetRate || null}
        currentRate={progress?.currentRate || null}
        p95={progress?.p95 || null}
        errorRate={progress?.errorRate || null}
        preparationMessage={progress?.message || null}
        resourceReading={progress?.resourceReading ?? null}
        onConfirmCancel={onCancel}
        cancelPending={cancelPending}
      />
    </Stack>
  );
}
