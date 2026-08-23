import { useQueryClient } from '@tanstack/react-query';
import { LiveExecutionPanel } from '../../components/LiveExecutionPanel';
import { useCancelRunMutation } from '../../api/run';
import { useRunProgress } from '../../api/runs';
import { invalidateService } from '../../api/workspace';
import type { RunRef } from '../../api/workspace';

/**
 * A test's own row, mid-run — the same live vocabulary {@link RunPage} has always shown, just
 * reached without leaving the service workspace to watch it. `running` is the service header's own
 * `RunRef`, already in hand on Overview with no extra fetch; this component's only job is turning it
 * into a live SSE subscription and handing the result to {@link LiveExecutionPanel}, the one live
 * surface both this row and the standalone run page share.
 *
 * <p>No seed fetch of the run itself: `RunRef.stateLabel` is a perfectly good stand-in for the first
 * few seconds before the first progress bucket arrives, the same way {@link RunPage} itself falls
 * back to `run.stateLabel` — adding a second query here just to close a five-second gap that already
 * self-heals would be a fetch with no reader-facing benefit.
 */
export function RunningTestPanel({ serviceId, running }: { serviceId: string; running: RunRef }) {
  const queryClient = useQueryClient();
  const cancelMutation = useCancelRunMutation(running.id);
  const progress = useRunProgress(running.id, {
    enabled: true,
    initialProgress: null,
    onFinished: () => invalidateService(queryClient, serviceId),
  });

  return (
    <LiveExecutionPanel
      density="compact"
      stage={progress?.stage ?? running.stateLabel}
      elapsed={progress?.elapsed ?? '00:00'}
      percent={progress?.percent ?? 0}
      targetRate={progress?.targetRate || null}
      currentRate={progress?.currentRate || null}
      p95={progress?.p95 || null}
      errorRate={progress?.errorRate || null}
      onConfirmCancel={() => cancelMutation.mutate()}
      cancelPending={cancelMutation.isPending}
    />
  );
}
