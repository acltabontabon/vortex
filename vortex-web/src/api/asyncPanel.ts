// The one shape behind four different features: analysis-panel, comparison-analysis-panel, and
// lab-panel each POST to start work Vortex does in the background (an AI call, a docker compose
// command), then GET-poll a status endpoint until it stops being "in progress." htmx expressed
// this as `hx-trigger="every Ns"` gated on a running flag; TanStack Query's `refetchInterval`
// gated the same way is the direct equivalent, and it already stops polling itself the instant
// the flag flips — no manual clearInterval bookkeeping.
//
// Workload preview is the one exception: it recomputes synchronously as the form changes, so it
// has no "running" state to poll and does not use this hook.

import { useMutation, useQuery, useQueryClient, type QueryKey } from '@tanstack/react-query';
import { apiClient } from './client';

const POLL_INTERVAL_MS = 2000;

export function useAsyncPanel<TStatus>({
  queryKey,
  statusPath,
  startPath,
  isRunning,
}: {
  queryKey: QueryKey;
  statusPath: string;
  /** Undefined when this panel has no start action of its own (status-only, e.g. lab status). */
  startPath?: string;
  isRunning: (status: TStatus) => boolean;
}) {
  const queryClient = useQueryClient();

  const status = useQuery({
    queryKey,
    queryFn: () => apiClient.get<TStatus>(statusPath),
    refetchInterval: (query) => {
      const data = query.state.data;
      return data && isRunning(data) ? POLL_INTERVAL_MS : false;
    },
  });

  const start = useMutation({
    mutationFn: () => apiClient.post<void>(startPath!),
    // Kicks the poll into its running state immediately rather than waiting for the next tick.
    onSuccess: () => queryClient.invalidateQueries({ queryKey }),
  });

  return { status, start };
}
