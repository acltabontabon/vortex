import { Alert, Button, Group, Stack, Text, TextInput } from '@mantine/core';
import { IconPlayerPlayFilled } from '@tabler/icons-react';
import { motion, useReducedMotion } from 'motion/react';
import type { Preflight } from '../../api/run';
import classes from './PreflightActions.module.css';

type PreflightActionsProps = {
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
  /** 'stacked' (default) is the drawer's pinned-footer treatment: a full-width status heading over
   *  a full-width Run. 'inline' is PreflightPage's title-row treatment: compact status + buttons
   *  sitting beside the heading instead of a whole separate card underneath it. */
  layout?: 'stacked' | 'inline';
};

function StatusDot({ ready }: { ready: boolean }) {
  const reducedMotion = useReducedMotion() === true;
  return (
    <motion.span
      className={`${classes.statusDot} ${ready ? classes.statusDotReady : classes.statusDotBlocked}`}
      aria-hidden="true"
      animate={ready && !reducedMotion ? { scale: [1, 1.4, 1], opacity: [1, 0.55, 1] } : undefined}
      transition={{ duration: 1.8, repeat: Infinity, ease: 'easeInOut' }}
    />
  );
}

export function PreflightActions({ layout = 'stacked', ...props }: PreflightActionsProps) {
  if (layout === 'inline') return <InlineActions {...props} />;
  return <StackedActions {...props} />;
}

/** PreflightPage's title-row treatment — see {@link PreflightActionsProps.layout}. */
function InlineActions({
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
}: Omit<PreflightActionsProps, 'layout'>) {
  const hasDetail = (!preflight.canRun && failingChecks.length > 0) || (needsConfirmation && preflight.canRun) || startError;

  return (
    <div className={classes.inlineWrap}>
      <div className={classes.inlineTopRow}>
        <Text size="sm" fw={600} c={preflight.canRun ? undefined : 'fail'} className={classes.inlineStatus}>
          <StatusDot ready={preflight.canRun} />
          {preflight.canRun ? 'Ready to run' : 'Cannot run yet'}
        </Text>

        <Group gap="xs" wrap="nowrap">
          {preflight.canRun ? (
            <Button
              size="sm"
              leftSection={<IconPlayerPlayFilled size={14} />}
              disabled={!confirmed}
              loading={pending}
              onClick={onStart}
            >
              Run
            </Button>
          ) : (
            <Button size="sm" variant="light" loading={rechecking} onClick={onRecheck}>
              Recheck
            </Button>
          )}
          <Button size="sm" variant="subtle" c="dimmed" onClick={onCancel}>
            Cancel
          </Button>
        </Group>
      </div>

      {hasDetail && (
        <Stack gap="xs" className={classes.inlineDetail}>
          {!preflight.canRun && failingChecks.length > 0 && (
            <Text size="sm" c="dimmed">
              {failingChecks.map((check) => check.detail).join(' ')}
            </Text>
          )}

          {needsConfirmation && preflight.canRun && (
            <TextInput
              label={`Type "${requiredChallenges.join('" or "')}" to confirm`}
              value={confirmation}
              onChange={(event) => onConfirmationChange(event.currentTarget.value)}
              classNames={{ input: classes.confirmInput }}
              size="sm"
            />
          )}

          {startError && (
            <Alert color="fail" title="Could not start this run">
              {startError}
            </Alert>
          )}
        </Stack>
      )}

      <Text size="xs" className={classes.provenanceInline}>
        {preflight.runnerLabel} · {preflight.scriptSourceLabel} · {preflight.fingerprintShortHash}
      </Text>
    </div>
  );
}

/** The drawer's pinned-footer treatment — see {@link PreflightActionsProps.layout}. */
function StackedActions({
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
}: Omit<PreflightActionsProps, 'layout'>) {
  return (
    <>
      <Text size="sm" fw={600} mb="sm" c={preflight.canRun ? undefined : 'fail'} className={classes.heading}>
        <StatusDot ready={preflight.canRun} />
        {preflight.canRun ? 'Ready to run' : 'Cannot run yet'}
      </Text>

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
