import { Alert, Button, Text } from '@mantine/core';
import { IconRefresh } from '@tabler/icons-react';
import { useEffect, useRef, useState } from 'react';
import classes from './AsyncPanel.module.css';

export interface AiAvailability {
  available: boolean;
  problem: string;
  remedy: string;
}

/** Ticks once a second while `isRunning`, so a caller can show elapsed time without polling. */
function useElapsedSeconds(isRunning: boolean, startedAt: number | null): number | null {
  const [elapsed, setElapsed] = useState<number | null>(null);

  useEffect(() => {
    if (!isRunning || startedAt === null) {
      setElapsed(null);
      return;
    }
    const tick = () => setElapsed(Math.max(0, Math.floor((Date.now() - startedAt) / 1000)));
    tick();
    const id = window.setInterval(tick, 1000);
    return () => window.clearInterval(id);
  }, [isRunning, startedAt]);

  return elapsed;
}

function formatElapsed(seconds: number): string {
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return `${minutes}m ${rest.toString().padStart(2, '0')}s`;
}

/**
 * The shell every "ask AI" panel renders through — running / failed / unavailable / has-a-result —
 * so the features built on {@link ../api/asyncPanel.useAsyncPanel} don't each reimplement this
 * state machine. The result itself is the caller's content, passed as children, since a run
 * analysis and a comparison analysis render genuinely different fields.
 *
 * <p>FAILED is its own state, distinct from "unavailable" (a provider-level problem, e.g. Ollama
 * not running) and from "no result yet" (never asked) — a specific attempt that did not work gets
 * its own message and a retry action, rather than silently looking identical to not-requested.
 */
export function AsyncPanel({
  title,
  isRunning,
  runningMessage,
  availability,
  hasResult,
  failed = false,
  failureMessage,
  onRetry,
  retrying = false,
  children,
}: {
  title: string;
  isRunning: boolean;
  runningMessage: string;
  availability?: AiAvailability;
  hasResult: boolean;
  failed?: boolean;
  failureMessage?: string | null;
  onRetry?: () => void;
  retrying?: boolean;
  children?: React.ReactNode;
}) {
  const wasRunning = useRef(false);
  const [startedAt, setStartedAt] = useState<number | null>(null);
  useEffect(() => {
    if (isRunning && !wasRunning.current) {
      setStartedAt(Date.now());
    }
    wasRunning.current = isRunning;
  }, [isRunning]);
  const elapsed = useElapsedSeconds(isRunning, startedAt);

  if (isRunning) {
    return (
      <div className={`${classes.panel} ${classes.running}`}>
        <div className={classes.header}>
          <span className={classes.pulse} aria-hidden="true" />
          {title}
          {elapsed !== null && <span className={classes.elapsed}>{formatElapsed(elapsed)}</span>}
        </div>
        <Text size="sm" c="dimmed">
          {runningMessage}
        </Text>
      </div>
    );
  }

  if (hasResult) {
    return <>{children}</>;
  }

  if (failed) {
    return (
      <div className={`${classes.panel} ${classes.failed}`}>
        <div className={classes.header}>Interpretation did not complete</div>
        <Text size="sm" c="dimmed">
          {failureMessage || 'The model did not return a usable response.'}
        </Text>
        {onRetry && (
          <Button
            onClick={onRetry}
            loading={retrying}
            variant="light"
            color="fail"
            size="xs"
            mt="sm"
            leftSection={<IconRefresh size={14} />}
          >
            Retry
          </Button>
        )}
      </div>
    );
  }

  if (availability && !availability.available) {
    return (
      <Alert color="fail" title="Local AI is not available">
        <Text size="sm">{availability.problem}</Text>
        <Text size="sm" mt="xs" style={{ whiteSpace: 'pre-line' }}>
          {availability.remedy}
        </Text>
        <Button component="a" href="/settings" size="xs" mt="sm" variant="light">
          Settings
        </Button>
      </Alert>
    );
  }

  return null;
}
