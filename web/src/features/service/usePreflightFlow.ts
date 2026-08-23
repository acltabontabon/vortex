import { useState } from 'react';
import { usePreflightQuery, useStartRunMutation, type StartRunResponse } from '../../api/run';
import { extractErrorMessage } from '../../lib/queryFallback';

/**
 * The query, the confirmation state and the start mutation behind Preflight — shared by the
 * standalone page and the in-place drawer, which differ only in what they do with a successful
 * start (navigate away vs. simply close) and how they lay the same facts out.
 */
export function usePreflightFlow(
  serviceId: string,
  workload: string | null,
  environment: string | null,
  objective: string | null,
) {
  const [confirmation, setConfirmation] = useState('');

  const preflightQuery = usePreflightQuery(serviceId, workload, environment, objective);
  const startMutation = useStartRunMutation(serviceId);
  const preflight = preflightQuery.data;

  const requiredChallenges = preflight?.requiredChallenges ?? [];
  const needsConfirmation = requiredChallenges.length > 0;
  const confirmed = !needsConfirmation || requiredChallenges.includes(confirmation.trim());
  const failingChecks = preflight?.checks.filter((check) => check.statusKind === 'FAIL') ?? [];

  function start(onSuccess: (response: StartRunResponse) => void) {
    if (!preflight) return;
    startMutation.mutate(
      {
        workload: preflight.workloadName,
        environment: preflight.environmentName,
        objective,
        confirmation,
      },
      { onSuccess },
    );
  }

  const startError =
    startMutation.data && !startMutation.data.started
      ? startMutation.data.error
      : extractErrorMessage(startMutation, 'Something went wrong starting this run.');

  return {
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
  };
}
