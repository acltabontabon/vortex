import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { Card, Skeleton, Stack } from '@mantine/core';
import { Unknown } from '../../components/Unknown';
import { errorFallback } from '../../lib/queryFallback';
import { usePreflightFlow } from './usePreflightFlow';
import { PreflightSections } from './PreflightSections';
import { PreflightActions } from './PreflightActions';
import classes from './PreflightPage.module.css';

/**
 * What will happen if you press Run, before anything is sent — the standalone destination for the
 * few places that still link here rather than opening the in-place drawer on Overview (running a
 * past run again from `RunPage`, `RunsPage` or `EvidencePage`, none of which are the service
 * workspace itself).
 */
export function PreflightPage() {
  const { id = '' } = useParams();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();

  const workload = searchParams.get('workload');
  const environment = searchParams.get('environment');
  const objective = searchParams.get('objective');

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
  } = usePreflightFlow(id, workload, environment, objective);

  const error = errorFallback(preflightQuery.isError, 'Could not prepare this run');
  if (error) return error;

  if (!preflight) return <Skeleton height={480} radius="md" />;

  if (preflight.error && !preflight.testTypeLabel) {
    return (
      <Unknown
        what={preflight.error}
        reason={preflight.errorDetails.join(' ') || null}
        actionLabel="Go to Configuration"
        actionHref={`/services/${id}/configuration`}
      />
    );
  }

  function onStart() {
    start((response) => {
      // Back to the service workspace, not a dedicated progress page — the run now shows live,
      // inline, on the test's own row there, so there's nothing this page needs `executionId` for
      // any more.
      if (response.started) {
        navigate(`/services/${id}`);
      }
    });
  }

  return (
    <Stack gap="lg" className={classes.split}>
      <div className={classes.main}>
        <PreflightSections preflight={preflight} />
      </div>

      <div className={classes.aside}>
        <Card withBorder radius="md" className={classes.stickyCard}>
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
            onCancel={() => navigate(`/services/${id}`)}
            onRecheck={() => preflightQuery.refetch()}
            rechecking={preflightQuery.isFetching}
          />
        </Card>
      </div>
    </Stack>
  );
}
