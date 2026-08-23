import { Alert, Button, Group, Text, TextInput, Title } from '@mantine/core';
import type { Preflight } from '../../api/run';

/**
 * The confirm-and-start controls — identical between the standalone Preflight page's sticky aside
 * and the in-place drawer's pinned footer, which only differ in the surface (Card vs. footer) they
 * sit inside and what "Cancel" navigates back to.
 */
export function PreflightActions({
  preflight,
  confirmation,
  onConfirmationChange,
  needsConfirmation,
  requiredChallenges,
  confirmed,
  failingChecks,
  startError,
  pending,
  onStart,
  onCancel,
  onRecheck,
  rechecking,
}: {
  preflight: Preflight;
  confirmation: string;
  onConfirmationChange: (value: string) => void;
  needsConfirmation: boolean;
  requiredChallenges: string[];
  confirmed: boolean;
  failingChecks: { detail: string }[];
  startError: string | null;
  pending: boolean;
  onStart: () => void;
  onCancel: () => void;
  onRecheck: () => void;
  rechecking: boolean;
}) {
  return (
    <>
      <Title order={3} size="h4" mb="sm">
        {preflight.canRun ? 'Ready to run' : 'Cannot run yet'}
      </Title>

      {!preflight.canRun && failingChecks.length > 0 && (
        <Text size="sm" c="dimmed" mb="sm">
          {failingChecks.map((check) => check.detail).join(' ')}
        </Text>
      )}

      {needsConfirmation && preflight.canRun && (
        <TextInput
          label={`Type "${requiredChallenges.join('" or "')}" to confirm`}
          value={confirmation}
          onChange={(event) => onConfirmationChange(event.currentTarget.value)}
          mb="sm"
        />
      )}

      {startError && (
        <Alert color="fail" title="Could not start this run" mb="sm">
          {startError}
        </Alert>
      )}

      <Group>
        {preflight.canRun ? (
          <Button size="lg" disabled={!confirmed} loading={pending} onClick={onStart}>
            Run
          </Button>
        ) : (
          <Button size="lg" variant="light" loading={rechecking} onClick={onRecheck}>
            Recheck
          </Button>
        )}
        <Button size="lg" variant="default" onClick={onCancel}>
          Cancel
        </Button>
      </Group>

      <Text size="xs" c="dimmed" mt="sm">
        {preflight.runnerLabel} · {preflight.scriptSourceLabel} · {preflight.fingerprintShortHash}
      </Text>
    </>
  );
}
