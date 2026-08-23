import { Drawer, Skeleton } from '@mantine/core';
import { Unknown } from '../../components/Unknown';
import { errorFallback } from '../../lib/queryFallback';
import { usePreflightFlow } from './usePreflightFlow';
import { PreflightSections } from './PreflightSections';
import { PreflightActions } from './PreflightActions';
import classes from './PreflightDrawer.module.css';

/**
 * Preflight, opened in place rather than navigated to. Same facts, same confirm-and-start flow as
 * {@link PreflightPage} (both run on {@link usePreflightFlow}) — the difference is only what happens
 * next: closing this drawer instead of navigating away, since the row that opened it already shows
 * the run live, inline, the moment it starts (`useStartRunMutation` invalidates the Overview query
 * that feeds it).
 */
export function PreflightDrawer({
  serviceId,
  workload,
  environment,
  opened,
  onClose,
}: {
  serviceId: string;
  workload: string;
  environment: string | null;
  opened: boolean;
  onClose: () => void;
}) {
  const {
    preflightQuery,
    preflight,
    confirmation,
    setConfirmation,
    requiredChallenges,
    needsConfirmation,
    confirmed,
    failingChecks,
    startMutation,
    startError,
    start,
  } = usePreflightFlow(serviceId, opened ? workload : null, environment, null);

  function onStart() {
    start((response) => {
      if (response.started) onClose();
    });
  }

  const error = errorFallback(preflightQuery.isError, 'Could not prepare this run');
  const unresolved = preflight?.error && !preflight.testTypeLabel;

  return (
    <Drawer
      opened={opened}
      onClose={onClose}
      position="right"
      size={640}
      title={preflight?.testTypeLabel ?? 'Run test'}
      padding="xl"
    >
      {error ? (
        error
      ) : !preflight ? (
        <Skeleton height={420} radius="md" />
      ) : unresolved ? (
        <Unknown
          what={preflight.error!}
          reason={preflight.errorDetails.join(' ') || null}
          actionLabel="Go to Configuration"
          actionHref={`/services/${serviceId}/configuration`}
        />
      ) : (
        <>
          <PreflightSections preflight={preflight} showHeading={false} />
          <div className={classes.footer}>
            <PreflightActions
              preflight={preflight}
              confirmation={confirmation}
              onConfirmationChange={setConfirmation}
              needsConfirmation={needsConfirmation}
              requiredChallenges={requiredChallenges}
              confirmed={confirmed}
              failingChecks={failingChecks}
              startError={startError}
              pending={startMutation.isPending}
              onStart={onStart}
              onCancel={onClose}
            />
          </div>
        </>
      )}
    </Drawer>
  );
}
