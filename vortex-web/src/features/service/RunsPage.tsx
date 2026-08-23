import { useParams } from 'react-router-dom';
import { Button, Skeleton, Stack, Text, Title } from '@mantine/core';
import { useRunsQuery } from '../../api/workspace';
import type { RunSummary } from '../../api/workspace';
import { Unknown } from '../../components/Unknown';
import { VerdictBadge } from '../../components/VerdictBadge';
import { runAgainHref } from '../../lib/testState';
import { errorFallback } from '../../lib/queryFallback';
import classes from './RunsPage.module.css';

/**
 * Every run Vortex has recorded for this service.
 *
 * <p>Runs are evidence, which is why this is not called History: each row is a claim about the
 * service at a moment, kept whole. Reopening one shows the test that actually ran, not what
 * today's configuration would produce — and where the two have since diverged, the row says so
 * rather than pretending they are the same.
 *
 * <p>`Run again` is on every row, not just the latest. If something ran twelve runs ago, re-running
 * it should not require reconstructing the test from memory.
 */
export function RunsPage() {
  const { id = '' } = useParams();
  const { data, isError } = useRunsQuery(id);

  const error = errorFallback(isError, "Could not load this service's runs",
      `/api/services/${id}/runs did not respond. Reload the page to try again.`);
  if (error) return error;

  if (!data) return <Skeleton height={320} radius="md" />;

  return (
    <Stack gap="lg">
      <div>
        <Title order={2} size="h4">
          Runs
        </Title>
        <Text size="sm" c="dimmed">
          Every run Vortex has recorded. Each one keeps the plan it executed, so editing or deleting
          a test later never changes what a run says it did.
        </Text>
      </div>

      {data.runs.length === 0 ? (
        <Unknown
          what="No runs yet."
          reason="Start with a smoke test — a very small amount of traffic for a few seconds. It confirms Vortex can reach the service and that the test is valid, before you generate load that means anything."
          actionLabel="Go to Tests"
          actionHref={`/services/${id}`}
        />
      ) : (
        <div className={classes.table}>
          <div className={classes.head}>
            <span>Result</span>
            <span>Test</span>
            <span>Level</span>
            <span>Environment</span>
            <span>Release</span>
            <span>When</span>
            <span />
          </div>
          {data.runs.map((run) => (
            <RunRow key={run.id} serviceId={id} run={run} />
          ))}
        </div>
      )}
    </Stack>
  );
}

function RunRow({ serviceId, run }: { serviceId: string; run: RunSummary }) {
  return (
    <div className={classes.row}>
      <a className={classes.cell} href={`/runs/${run.id}`}>
        <VerdictBadge verdict={run.verdict} label={run.verdictLabel} />
      </a>
      <a className={`${classes.cell} ${classes.test}`} href={`/runs/${run.id}`}>
        {run.testName}
        <span className={classes.testType}>{run.testTypeLabel}</span>
      </a>
      <span className={classes.cell}>{run.levelDisplay}</span>
      <span className={classes.cell}>{run.environmentName}</span>
      <span className={classes.cell}>{run.release ?? '—'}</span>
      <span className={`${classes.cell} ${classes.when}`}>{run.relativeTime}</span>
      <span className={classes.cell}>
        {/* False means the test moved since this ran — never pretended away. */}
        {run.matchesCurrentTest === false && (
          <span className={classes.drifted} title={run.differences.join(' · ')}>
            test has changed
          </span>
        )}
        <Button
          component="a"
          href={runAgainHref(serviceId, run)}
          size="compact-xs"
          variant="default"
        >
          Run again
        </Button>
      </span>
    </div>
  );
}
