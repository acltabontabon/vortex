import { Button, Progress, Text } from '@mantine/core';
import { modals } from '@mantine/modals';
import { IconPlayerStop } from '@tabler/icons-react';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import { useRunningCommentary } from '../lib/runningCommentary';
import classes from './LiveExecutionPanel.module.css';

/**
 * A test, mid-run — the one canonical live-execution surface, shared by the service row's own
 * inline view and the standalone run page, so the two never drift into two different vocabularies
 * for the same thing. `density` only changes sizing/typography; the structure is identical either
 * way, which is what keeps them consistent by construction rather than by convention.
 *
 * <p>No `testTypeLabel`/kicker prop: both call sites already state that fact one line above where
 * this panel mounts, so it isn't repeated here. Every telemetry field is nullable and simply omits
 * its own slot when absent — the same filtering behaviour the panel this replaces already had —
 * because a blank "p95 —" is a claim about data Vortex doesn't have, and this app never states one.
 *
 * <p>Owns its own cancel confirmation (`@mantine/modals`, the same pattern every other destructive
 * action in this app uses) so both call sites ask the same question the same way, rather than each
 * inventing its own copy.
 */
export function LiveExecutionPanel({
  density,
  stage,
  elapsed,
  percent,
  targetRate,
  currentRate,
  p95,
  errorRate,
  onConfirmCancel,
  cancelPending,
}: {
  density: 'compact' | 'full';
  stage: string;
  elapsed: string;
  percent: number;
  targetRate: string | null;
  currentRate: string | null;
  p95: string | null;
  errorRate: string | null;
  onConfirmCancel: () => void;
  cancelPending: boolean;
}) {
  const reducedMotion = useReducedMotion() === true;
  const full = density === 'full';

  function onCancelClick() {
    modals.openConfirmModal({
      title: 'Cancel this run?',
      children: (
        <Text size="sm">
          This stops the run in progress. Whatever it has measured so far is discarded — there is
          no partial result to fall back to.
        </Text>
      ),
      labels: { confirm: 'Cancel run', cancel: 'Keep running' },
      confirmProps: { color: 'fail' },
      onConfirm: onConfirmCancel,
    });
  }

  return (
    <div
      className={`${classes.panel} ${full ? classes.full : ''}`}
      data-density={density}
      data-reduced-motion={reducedMotion ? 'true' : undefined}
    >
      <div className={classes.headRow}>
        <span className={classes.stage}>{stage}</span>
        <div className={classes.liveMeta}>
          <span className={classes.live}>
            <span className={classes.liveDot} aria-hidden="true" />
            LIVE
          </span>
          <span className={classes.elapsed}>{elapsed}</span>
        </div>
      </div>

      <Progress
        value={percent}
        size={full ? 'lg' : 'sm'}
        radius="sm"
        color="live"
        animated={!reducedMotion}
      />

      <div className={classes.telemetry}>
        <TelemetryStat label="Target" value={targetRate} />
        <TelemetryStat label="Actual" value={currentRate} />
        <TelemetryStat label="p95" value={p95} />
        <TelemetryStat label="Errors" value={errorRate} />
      </div>

      <CommentaryLine reducedMotion={reducedMotion} />

      <div className={classes.footer}>
        <Button
          color="fail"
          variant="subtle"
          size={full ? 'sm' : 'xs'}
          leftSection={<IconPlayerStop size={14} />}
          loading={cancelPending}
          onClick={onCancelClick}
        >
          Cancel run
        </Button>
      </div>
    </div>
  );
}

function TelemetryStat({ label, value }: { label: string; value: string | null }) {
  if (!value) return null;
  return (
    <div className={classes.stat}>
      <span className={classes.statLabel}>{label}</span>
      <span className={classes.statValue}>{value}</span>
    </div>
  );
}

/**
 * Isolated in its own component so its rotation timer's re-renders stay local to this one line —
 * never the panel around it, never whatever mounts the panel. Reduced motion still rotates the
 * text, just without the crossfade: the requirement is no animated transition, not frozen content.
 */
function CommentaryLine({ reducedMotion }: { reducedMotion: boolean }) {
  const line = useRunningCommentary();

  if (reducedMotion) {
    return <p className={classes.commentary}>↳ {line}</p>;
  }

  return (
    <AnimatePresence mode="wait">
      <motion.p
        key={line}
        className={classes.commentary}
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        transition={{ duration: 0.2 }}
      >
        ↳ {line}
      </motion.p>
    </AnimatePresence>
  );
}
