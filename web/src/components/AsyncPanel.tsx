import { Alert, Button, Text } from '@mantine/core';
import classes from './AsyncPanel.module.css';

export interface AiAvailability {
  available: boolean;
  problem: string;
  remedy: string;
}

/**
 * The shell every "ask AI" panel renders through — running / unavailable / has-a-result — so the
 * four features built on {@link ../api/asyncPanel.useAsyncPanel} don't each reimplement this
 * triad. The result itself is the caller's content, passed as children, since a run analysis and
 * a comparison analysis render genuinely different fields.
 */
export function AsyncPanel({
  title,
  isRunning,
  runningMessage,
  availability,
  hasResult,
  children,
}: {
  title: string;
  isRunning: boolean;
  runningMessage: string;
  availability?: AiAvailability;
  hasResult: boolean;
  children?: React.ReactNode;
}) {
  if (isRunning) {
    return (
      <div className={classes.panel}>
        <div className={classes.header}>{title}</div>
        <Text size="sm" c="dimmed">
          {runningMessage}
        </Text>
      </div>
    );
  }

  if (hasResult) {
    return <>{children}</>;
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
