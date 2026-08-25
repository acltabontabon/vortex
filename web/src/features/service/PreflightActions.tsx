import { Alert, Button, Stack, Text, TextInput, Title } from '@mantine/core';
import { IconPlayerPlayFilled } from '@tabler/icons-react';
import { motion, useReducedMotion } from 'motion/react';
import type { Preflight } from '../../api/run';
import classes from './PreflightActions.module.css';

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
  const reducedMotion = useReducedMotion() === true;

  return (
    <>
      {(preflight.peakLevelDisplay || preflight.durationDisplay) && (
        <div className={classes.statRow}>
          {preflight.peakLevelDisplay && (
            <div className={classes.stat}>
              <div className={classes.statValue}>{preflight.peakLevelDisplay}</div>
              <div className={classes.statLabel}>Level</div>
            </div>
          )}
          {preflight.durationDisplay && (
            <div className={classes.stat}>
              <div className={classes.statValue}>{preflight.durationDisplay}</div>
              <div className={classes.statLabel}>Duration</div>
            </div>
          )}
        </div>
      )}

      <Title
        order={3}
        size="h4"
        mb="sm"
        className={classes.heading}
        c={preflight.canRun ? undefined : 'fail'}
      >
        <motion.span
          className={`${classes.statusDot} ${preflight.canRun ? classes.statusDotReady : classes.statusDotBlocked}`}
          aria-hidden="true"
          animate={
            preflight.canRun && !reducedMotion ? { scale: [1, 1.4, 1], opacity: [1, 0.55, 1] } : undefined
          }
          transition={{ duration: 1.8, repeat: Infinity, ease: 'easeInOut' }}
        />
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
          classNames={{ input: classes.confirmInput }}
          mb="sm"
        />
      )}

      {startError && (
        <Alert color="fail" title="Could not start this run" mb="sm">
          {startError}
        </Alert>
      )}

      <Stack gap="xs">
        {preflight.canRun ? (
          <Button
            size="lg"
            fullWidth
            leftSection={<IconPlayerPlayFilled size={16} />}
            disabled={!confirmed}
            loading={pending}
            onClick={onStart}
          >
            Run
          </Button>
        ) : (
          <Button size="lg" fullWidth variant="light" loading={rechecking} onClick={onRecheck}>
            Recheck
          </Button>
        )}
        <Button size="sm" variant="subtle" c="dimmed" onClick={onCancel}>
          Cancel
        </Button>
      </Stack>

      <Text size="xs" className={classes.provenance}>
        {preflight.runnerLabel} · {preflight.scriptSourceLabel} · {preflight.fingerprintShortHash}
      </Text>
    </>
  );
}
