import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Button, Group, Select, Skeleton, Stack, Text, Title } from '@mantine/core';
import { useRunHistoryQuery } from '../../api/globalRuns';
import type { RunHistoryRow } from '../../api/globalRuns';
import { Unknown } from '../../components/Unknown';
import { VerdictBadge } from '../../components/VerdictBadge';
import { errorFallback } from '../../lib/queryFallback';
import classes from './AllRunsPage.module.css';

const ANY = '__any__';

/**
 * Every run Vortex has recorded, across every service.
 *
 * <p>Runs are evidence, which is why this is not called History: each row is a claim about a
 * service at a moment, kept whole. The filters offer only values this history actually contains, so
 * no combination can name something that was never run.
 *
 * <p>Selecting exactly two rows offers to compare them — the entry point {@code compare.html} never
 * had, reachable before only by typing the URL by hand.
 */
export function AllRunsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();
  const [selected, setSelected] = useState<string[]>([]);

  const filters = {
    project: searchParams.get('project'),
    evaluation: searchParams.get('evaluation'),
    workload: searchParams.get('workload'),
    environment: searchParams.get('environment'),
    result: searchParams.get('result'),
  };

  const { data, isError } = useRunHistoryQuery(filters);

  function setFilter(key: string, value: string | null) {
    const next = new URLSearchParams(searchParams);
    if (!value || value === ANY) next.delete(key);
    else next.set(key, value);
    setSearchParams(next);
  }

  function toggle(id: string) {
    setSelected((current) =>
      current.includes(id) ? current.filter((x) => x !== id) : [...current, id].slice(-2)
    );
  }

  const rowsById = useMemo(
    () => new Map((data?.rows ?? []).map((row) => [row.executionId, row])),
    [data]
  );

  const error = errorFallback(isError, 'Could not load run history',
      '/api/runs did not respond. Reload the page to try again.');
  if (error) return error;

  if (!data) return <Skeleton height={420} radius="md" />;

  return (
    <Stack gap="lg">
      <div>
        <Title order={1} size="h2">
          Runs
        </Title>
        <Text size="sm" c="dimmed">
          Every run Vortex has recorded. Each one keeps the plan it executed, so editing or deleting
          a test later never changes what a run says it did.
        </Text>
      </div>

      {data.totalBeforeFilters > 0 && (
        <Group gap="sm" align="flex-end" wrap="wrap">
          <Select
            label="Service"
            data={[{ value: ANY, label: 'Any' }, ...data.projects.map((p) => ({ value: p.id, label: p.name }))]}
            value={filters.project ?? ANY}
            onChange={(value) => setFilter('project', value)}
            w={180}
          />
          <Select
            label="Evaluation"
            data={[ANY, ...data.evaluations].map((v) => ({ value: v, label: v === ANY ? 'Any' : v }))}
            value={filters.evaluation ?? ANY}
            onChange={(value) => setFilter('evaluation', value)}
            w={160}
          />
          <Select
            label="Workload"
            data={[ANY, ...data.workloadNames].map((v) => ({ value: v, label: v === ANY ? 'Any' : v }))}
            value={filters.workload ?? ANY}
            onChange={(value) => setFilter('workload', value)}
            w={160}
          />
          <Select
            label="Environment"
            data={[ANY, ...data.environments].map((v) => ({ value: v, label: v === ANY ? 'Any' : v }))}
            value={filters.environment ?? ANY}
            onChange={(value) => setFilter('environment', value)}
            w={150}
          />
          <Select
            label="Result"
            data={[ANY, ...data.results].map((v) => ({ value: v, label: v === ANY ? 'Any' : v }))}
            value={filters.result ?? ANY}
            onChange={(value) => setFilter('result', value)}
            w={140}
          />
          {(filters.project || filters.evaluation || filters.workload || filters.environment || filters.result) && (
            <Button variant="default" onClick={() => setSearchParams(new URLSearchParams())}>
              Clear
            </Button>
          )}
        </Group>
      )}

      {data.rows.length === 0 ? (
        <Unknown
          what={data.totalBeforeFilters === 0 ? 'No runs yet.' : 'No runs match those filters.'}
          reason={
            data.totalBeforeFilters === 0
              ? 'Start with a smoke test — a very small amount of traffic for a few seconds. It confirms Vortex can reach a service and that the test is valid, before you generate load that means anything.'
              : null
          }
          actionLabel={data.totalBeforeFilters === 0 ? 'Choose a service' : 'Clear filters'}
          actionHref={data.totalBeforeFilters === 0 ? '/' : undefined}
        />
      ) : (
        <>
          <div className={classes.table}>
            <div className={classes.head}>
              <span />
              <span>Service</span>
              <span>Evaluation</span>
              <span>Workload</span>
              <span>Environment</span>
              <span>Result</span>
              <span>Offered</span>
              <span>Achieved</span>
              <span>p95</span>
              <span>When</span>
            </div>
            {data.rows.map((row) => (
              <RunRow
                key={row.executionId}
                row={row}
                checked={selected.includes(row.executionId)}
                onToggle={() => toggle(row.executionId)}
              />
            ))}
          </div>
          <Text size="xs" c="dimmed">
            Rates are requests per second. A run driven by concurrent virtual users offered a number
            of clients rather than a rate, so its achieved throughput is an outcome — the run's own
            page says which model it used.
          </Text>
        </>
      )}

      {selected.length === 2 && (
        <Group className={classes.compareBar} gap="sm">
          <Text size="sm">
            Compare <strong>{rowsById.get(selected[0])?.workloadName}</strong> and{' '}
            <strong>{rowsById.get(selected[1])?.workloadName}</strong>?
          </Text>
          <Button
            onClick={() =>
              navigate(`/runs/compare?baseline=${selected[0]}&candidate=${selected[1]}`)
            }
          >
            Compare
          </Button>
        </Group>
      )}
    </Stack>
  );
}

function RunRow({
  row,
  checked,
  onToggle,
}: {
  row: RunHistoryRow;
  checked: boolean;
  onToggle: () => void;
}) {
  return (
    <div className={classes.row}>
      <span className={classes.cell}>
        <input type="checkbox" checked={checked} onChange={onToggle} aria-label={`Select ${row.workloadName}`} />
      </span>
      <a className={classes.cell} href={`/runs/${row.executionId}`}>
        {row.projectName}
        {row.serviceVersion && <span className={classes.dim}> {row.serviceVersion}</span>}
      </a>
      <span className={classes.cell}>{row.testTypeLabel}</span>
      <a className={classes.cell} href={`/services/${row.projectId}/tests/${row.workloadName}/edit`}>
        {row.workloadName}
      </a>
      <span className={classes.cell}>
        {row.environmentName}
        <span className={classes.dim}> {row.classificationLabel}</span>
      </span>
      <span className={classes.cell}>
        {row.terminal ? (
          <VerdictBadge verdict={row.verdict} label={row.verdictLabel} />
        ) : (
          <span className={classes.running}>{row.stateLabel}</span>
        )}
      </span>
      <span className={classes.cell}>{row.offeredLoadDisplay}</span>
      <span className={classes.cell}>{row.achievedRateDisplay ?? '—'}</span>
      <span className={classes.cell}>{row.p95Display ?? '—'}</span>
      <span className={`${classes.cell} ${classes.dim}`}>{row.relativeTime}</span>
    </div>
  );
}
