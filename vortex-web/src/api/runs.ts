// Field-for-field against the payload dev.vortex.app.web.RunApiController's SSE stream sends (see
// toProgressDto in RunApiController.java) — one pre-aggregated bucket every five seconds, not
// individual samples, so the browser cost stays negligible next to the run itself.

import { useEffect, useRef, useState } from 'react';
import { apiClient } from './client';

export interface ResourceReading {
  cpu: string;
  memory: string;
}

export interface RunProgress {
  state: string;
  elapsed: string;
  stage: string;
  percent: number;
  targetRate: string;
  currentRate: string;
  p95: string;
  errorRate: string;
  // A human-readable target-preparation status line, populated only while state === 'STARTING' for
  // a Docker/Compose target being created — see LiveExecutionPanel's own docstring for why this is
  // rendered verbatim, never parsed into a checklist. Empty for an ordinary external-endpoint run.
  message: string;
  // The target's live CPU/memory reading, or null — always null in this build (see
  // ExecutionProgress.currentResourceReading's javadoc on the backend for why).
  resourceReading?: ResourceReading | null;
}

const MAX_RECONNECT_FAILURES = 3;
const FALLBACK_POLL_MS = 3000;

/**
 * Subscribes to `/api/runs/{id}/stream` while a run is in flight.
 *
 * The browser's own EventSource reconnect handles a transient drop; only after several
 * consecutive failures — the kind a proxy or browser that has fully given up on the stream
 * produces — does this fall back to plain polling, so a run is never silently stuck on stale
 * progress. `initialProgress`, seeded from the same one-shot GET the page already made to learn
 * whether the run is terminal, means a hard refresh mid-run shows the last known bucket
 * immediately instead of a blank screen for the first five seconds.
 */
export function useRunProgress(
  executionId: string,
  options: { enabled: boolean; initialProgress?: RunProgress | null; onFinished: () => void }
) {
  const [progress, setProgress] = useState<RunProgress | null>(options.initialProgress ?? null);
  const onFinishedRef = useRef(options.onFinished);
  onFinishedRef.current = options.onFinished;

  useEffect(() => {
    if (!options.enabled) {
      return;
    }

    let cancelled = false;
    let reconnectFailures = 0;
    let pollTimer: ReturnType<typeof setInterval> | undefined;
    const source = new EventSource(`/api/runs/${executionId}/stream`);

    function stopPolling() {
      if (pollTimer !== undefined) {
        clearInterval(pollTimer);
        pollTimer = undefined;
      }
    }

    function startPolling() {
      if (pollTimer !== undefined) {
        return;
      }
      pollTimer = setInterval(async () => {
        try {
          const run = await apiClient.get<{ running: boolean; progress: RunProgress | null }>(
            `/api/runs/${executionId}`
          );
          if (cancelled) {
            return;
          }
          if (run.progress) {
            setProgress(run.progress);
          }
          if (!run.running) {
            stopPolling();
            onFinishedRef.current();
          }
        } catch {
          // A transient fetch failure while polling is not worth surfacing — the next tick retries.
        }
      }, FALLBACK_POLL_MS);
    }

    source.addEventListener('progress', (event) => {
      reconnectFailures = 0;
      setProgress(JSON.parse((event as MessageEvent).data));
    });

    source.addEventListener('finished', () => {
      source.close();
      stopPolling();
      onFinishedRef.current();
    });

    source.onerror = () => {
      reconnectFailures += 1;
      if (reconnectFailures > MAX_RECONNECT_FAILURES) {
        source.close();
        startPolling();
      }
    };

    return () => {
      cancelled = true;
      source.close();
      stopPolling();
    };
  }, [executionId, options.enabled]);

  return progress;
}
