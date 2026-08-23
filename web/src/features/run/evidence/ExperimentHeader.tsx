import { Alert, Button, Text, Title } from '@mantine/core';
import type { RunIdentity, VerdictSection as VerdictSectionData } from '../../../api/run';
import { Fact, Facts } from '../../../components/Fact';
import { VerdictBadge } from '../../../components/VerdictBadge';
import classes from './ExperimentHeader.module.css';

/**
 * The experiment's identity, compacted to one strip — the same "quiet strip, not a heavy card" shape
 * {@code ServiceHeader} already established for a service's own identity, applied here to one run.
 *
 * <p>Everything a reader checks in the first second (name, result, test type, target, when) sits on
 * two lines. Everything else — exact configuration, identifiers, ownership — moves behind
 * "Experiment details ▾", the same `<details>` disclosure the rest of this page uses throughout
 * rather than a third UI pattern.
 */
export function ExperimentHeader({
  identity,
  verdict,
  releaseMoved,
  serviceId,
  executionId,
  runAgainHref,
  previousCompatibleExecutionId,
  variant,
}: {
  identity: RunIdentity;
  verdict: VerdictSectionData;
  releaseMoved: boolean;
  serviceId: string;
  executionId: string;
  runAgainHref: string | null;
  previousCompatibleExecutionId: string | null;
  variant: 'page' | 'report';
}) {
  return (
    <header className={classes.header}>
      <div className={classes.titleRow}>
        <Title order={1} size="h2" className={classes.name}>
          {identity.workloadName}
        </Title>
        {variant === 'page' && (
          <div className={classes.actions}>
            {runAgainHref && (
              <Button component="a" href={runAgainHref} variant="default" size="xs">
                Run again
              </Button>
            )}
            <Button component="a" href={`/runs/${executionId}/report`} variant="default" size="xs">
              Share report
            </Button>
            {previousCompatibleExecutionId ? (
              <Button
                component="a"
                href={`/runs/compare?baseline=${previousCompatibleExecutionId}&candidate=${executionId}`}
                variant="default"
                size="xs"
              >
                Compare with previous run
              </Button>
            ) : null}
          </div>
        )}
      </div>

      <div className={classes.summaryLine}>
        <VerdictBadge verdict={verdict.verdict as 'PASS' | 'FAIL' | 'NOT_EVALUATED'} label={verdict.verdictLabel} />
        <span className={classes.dot} aria-hidden="true" />
        <span>{identity.testTypeLabel}</span>
        <span className={classes.dot} aria-hidden="true" />
        <span>{identity.environmentName}</span>
        {identity.durationDisplay && (
          <>
            <span className={classes.dot} aria-hidden="true" />
            <span>{identity.durationDisplay}</span>
          </>
        )}
        <span className={classes.dot} aria-hidden="true" />
        <span>{identity.finishedAtDisplay}</span>
      </div>

      <div className={classes.metaLine}>
        {identity.targetKind !== 'EXTERNAL_ENDPOINT' && <span>{identity.targetSummary}</span>}
        {identity.targetKind === 'EXTERNAL_ENDPOINT' && <span>{identity.targetUrl}</span>}
        {identity.resourceSummary && (
          <>
            <span className={classes.dot} aria-hidden="true" />
            <span>{identity.resourceSummary}</span>
          </>
        )}
      </div>

      {releaseMoved && (
        <Alert color="live" title="This service has changed since this run" mt="sm">
          <Text size="sm" mb="xs">
            This evidence describes a different release than what is configured now.
          </Text>
          <Button component="a" href={`/services/${serviceId}`} size="xs" variant="light">
            Test current release
          </Button>
        </Alert>
      )}

      <details className={classes.details}>
        <summary>Experiment details</summary>
        <Facts>
          <Fact label="Target" note={identity.targetWasRewritten ? identity.targetRewriteReason : undefined}>
            {identity.targetUrl}
          </Fact>
          <Fact label="Target ownership">{identity.targetOwnershipLabel}</Fact>
          <Fact label="Environment">
            {identity.environmentName} — {identity.environmentTypeLabel}
          </Fact>
          <Fact label="Classification">{identity.classificationLabel}</Fact>
          {identity.serviceVersion && <Fact label="Release">{identity.serviceVersion}</Fact>}
          <Fact label="Run id">{identity.executionId}</Fact>
        </Facts>
      </details>
    </header>
  );
}
