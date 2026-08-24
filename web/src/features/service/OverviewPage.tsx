import type { ReactNode } from 'react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { Alert, Button, Grid, Skeleton, Stack, Text, Title } from '@mantine/core';
import { useElementSize, useMediaQuery } from '@mantine/hooks';
import { IconAlertTriangle, IconPlus } from '@tabler/icons-react';
import { useOverviewQuery } from '../../api/workspace';
import type { Overview } from '../../api/workspace';
import { UnknownInline } from '../../components/Unknown';
import { InfoPopover } from '../../components/InfoPopover';
import { shortRate } from '../../lib/testState';
import { errorFallback } from '../../lib/queryFallback';
import { resolveSelectedTest } from './selectTest';
import { TestRow } from './TestRow';
import { TestComposer } from './TestComposer';
import { WorkloadPreviewPanel, type ComposerPreviewSnapshot } from './WorkloadPreviewPanel';
import { RecentRunsRail } from './RecentRunsRail';
import { EvidenceStrip } from './EvidenceStrip';
import { ServiceVortex } from './ServiceVortex';
import classes from './OverviewPage.module.css';

/**
 * Whether this service can be measured at all yet.
 *
 * <p>Deliberately these two fields and no others. Not `readiness.canRun`, which is a different
 * question — it is also false for a service that has a target and an imported API and simply has
 * not defined a workload yet, and that service has a real Tests section to show and a "create one"
 * to offer. Gating on it would swallow the working page *and* deadlock the very action that fixes
 * it.
 */
function isUnconfigured(overview: Overview): boolean {
  return overview.header.target === null || overview.header.operationCount === 0;
}

/** Scrolls a test's row into view if it isn't already — jsdom has no `scrollIntoView` at all;
 *  every real browser does. */
function scrollTestRowIntoView(name: string): void {
  const row = document.querySelector(`[data-test-row="${CSS.escape(name)}"]`);
  if (row && typeof row.scrollIntoView === 'function') {
    row.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }
}

/**
 * Whether the Tests section is browsing its list or composing one test — `create` and `edit` carry
 * exactly what distinguishes them (nothing, and which test). Backed by `?compose=`/`?composeTest=` so
 * the composer is shareable/back-button-safe the same way `?test=` already is, but this is UI state
 * with URL persistence, not a routing layer of its own — one function, no state machine.
 */
type ComposerState = { mode: 'closed' } | { mode: 'create' } | { mode: 'edit'; name: string };

function resolveComposerState(overview: Overview, params: URLSearchParams): ComposerState {
  const compose = params.get('compose');
  if (compose === 'new') return { mode: 'create' };
  if (compose === 'edit') {
    const name = params.get('composeTest') ?? '';
    // A stale/bookmarked link to a test since renamed or deleted falls back to closed rather than
    // opening a composer for a test that no longer resolves.
    return overview.tests.some((test) => test.name === name) ? { mode: 'edit', name } : { mode: 'closed' };
  }
  return { mode: 'closed' };
}

/**
 * The operational landing page — a workbench, not a document.
 *
 * <p>Every figure below is one the domain computed, shown with its unit; where the domain refused to
 * compute something, the refusal itself is always visible, but the *elaboration* behind a refusal, a
 * provenance detail, or a condition list is one click away rather than permanently on the page.
 *
 * <p>Two horizontal layers, not a page of equally-weighted sections. The service header and the fact
 * grid below it are one continuous, quietly-tinted band — "everything you need to know about this
 * service before you touch a test" — and {@link FactGrid} is styled as that band's own continuation
 * (matching surface, corners rounded only where the band ends, pulled flush against the header to
 * cancel the layout's normal inter-section gap) rather than as a third fact-grid-shaped section in
 * its own right. Below that band is the working area: Tests, substantially wider and visually
 * dominant, beside Recent Runs, deliberately narrower and quieter — the two begin on the same row of
 * a single {@link Grid}, so nothing needs a vertical rule to say where one ends and the other begins;
 * width, gutter and typography already say it.
 *
 * <p>The page is a strict hierarchy — service, tests, a selected test, that test's own latest run,
 * that run's evidence — and every fact has exactly one canonical home in it. The fact grid owns
 * production/objectives at a glance, plus the service's single most-recently-demonstrated capacity
 * figure (itself now labelled with which test produced it, since that was never visible before, and
 * quietly rather than only on hover — ownership is not the kind of fact worth a click to see);
 * {@link TestRow} owns both a test's identity/actions *and*, expanded, that one test's own result and
 * evidence inline — never a different test's, and never the fact grid's own service-wide figure
 * repeated. Evidence lives inside the row it belongs to rather than in a separate section elsewhere
 * on the page, because proximity is the least ambiguous provenance there is. The rail owns activity,
 * and selecting a run there only ever expands which test's row is showing evidence — scrolling it
 * into view if needed — never claims to load a specific historical run's evidence. Where the same
 * figure could legitimately appear twice — a test's level inside its own evidence instrument, say —
 * it earns that second appearance by doing a different job there (labelling a mark on a scale, not
 * repeating a KPI). Where it can't earn that, it doesn't appear twice.
 */
export function OverviewPage() {
  const { id = '' } = useParams();
  const { data, isError } = useOverviewQuery(id);
  const [params, setParams] = useSearchParams();

  // How tall the Tests column actually renders to, so Recent Runs can grow to use the space beside
  // it rather than always stopping at its own minimum — see the doc comment on RecentRunsRail's
  // `fitHeight` prop for how that height becomes a row count. Only meaningful once Tests and Recent
  // Runs are genuinely side by side (the `md` breakpoint below, matching Grid.Col's own breakpoint) —
  // stacked at narrower widths, "beside it" has no meaning, so the rail just shows its minimum there.
  const { ref: testsColumnRef, height: testsColumnHeight } = useElementSize<HTMLDivElement>();
  const isSideBySide = useMediaQuery('(min-width: 62em)');

  const requestedName = params.get('test');
  const selectedTest = useMemo(
    () => (data ? resolveSelectedTest(data, requestedName) : null),
    [data, requestedName],
  );

  const composerState = useMemo(
    () => (data ? resolveComposerState(data, params) : { mode: 'closed' as const }),
    [data, params],
  );
  // The composer's own live workload, republished by TestComposer as the form changes — lifted
  // here because it's needed by a sibling (the rail slot below), not because Overview has any
  // opinion of its own about it.
  const [composerPreview, setComposerPreview] = useState<ComposerPreviewSnapshot | null>(null);

  function openCreate() {
    setParams((next) => {
      next.set('compose', 'new');
      next.delete('composeTest');
      return next;
    }, { replace: true });
  }

  function openEdit(name: string) {
    setParams((next) => {
      next.set('compose', 'edit');
      next.set('composeTest', name);
      return next;
    }, { replace: true });
  }

  function closeComposer() {
    setParams((next) => {
      next.delete('compose');
      next.delete('composeTest');
      return next;
    }, { replace: true });
  }

  // Bring the selected test's row into view when selection changes from elsewhere on the page (the
  // Recent Runs rail, most often) — a no-op when the row is already visible, since scrollIntoView's
  // "nearest" block only moves the viewport when the target genuinely isn't in it.
  useEffect(() => {
    if (!selectedTest) return;
    scrollTestRowIntoView(selectedTest.name);
  }, [selectedTest]);

  function selectTest(name: string) {
    setParams((next) => {
      // Clicking the row that's already expanded collapses it — compared against the resolved
      // selection, not the raw param, so this also works the very first time somebody collapses
      // whichever test the initial-selection rules opened by default (no explicit ?test= yet).
      next.set('test', selectedTest?.name === name ? '' : name);
      return next;
    }, { replace: true });
  }

  // Unlike `selectTest`, never toggles — a run that just finished should always end up expanded,
  // even if somebody had already collapsed this very row while it was running.
  function forceSelectTest(name: string) {
    setParams((next) => {
      next.set('test', name);
      return next;
    }, { replace: true });
  }

  // The test currently executing, if any — followed rather than merely displayed: the moment one
  // starts, its row has already jumped to the top of the sorted list above, and here is where that
  // move gets chased into view. The moment it *stops*, this is also where the row that just produced
  // a fresh result gets expanded — nobody has to click Expand on the test they were just watching.
  const runningTestName = data?.header.running?.testName ?? null;
  const previousRunningRef = useRef<string | null>(null);
  useEffect(() => {
    const previous = previousRunningRef.current;
    previousRunningRef.current = runningTestName;

    if (runningTestName && runningTestName !== previous) {
      scrollTestRowIntoView(runningTestName);
    } else if (previous && !runningTestName) {
      forceSelectTest(previous);
      scrollTestRowIntoView(previous);
    }
  }, [runningTestName]);

  const error = errorFallback(isError, 'Could not load this service',
      `/api/services/${id}/overview did not respond. Reload the page to try again.`);
  if (error) return error;

  if (!data) return <Skeleton height={420} radius="md" />;

  // Below every hook on purpose, and it has to stay there — the element/media measurements above
  // run unconditionally so this branch can exist at all.
  if (isUnconfigured(data)) {
    return (
      <div className={classes.page}>
        <div className={classes.vortexBand}>
          <ServiceVortex readiness={data.header.readiness} serviceId={id} />
        </div>

        {/* Still shown: what Attention says is a claim about evidence, not about setup. */}
        <Attention overview={data} serviceId={id} />
      </div>
    );
  }

  return (
    <div className={classes.page}>
      <FactGrid overview={data} serviceId={id} onSelectTest={selectTest} />

      <Attention overview={data} serviceId={id} />

      <Grid columnGap={48} rowGap="xl" align="start">
        <Grid.Col span={{ base: 12, md: 9 }} ref={testsColumnRef}>
          <TestsSection
            overview={data}
            serviceId={id}
            selectedName={selectedTest?.name ?? null}
            onSelect={selectTest}
            composerState={composerState}
            onCreateTest={openCreate}
            onEditTest={openEdit}
            onCloseComposer={closeComposer}
            onPreviewChange={setComposerPreview}
            showInlineChart={!isSideBySide}
          />
        </Grid.Col>
        <Grid.Col span={{ base: 12, md: 3 }} className={classes.railCol}>
          {composerState.mode !== 'closed' ? (
            <WorkloadPreviewPanel
              serviceName={data.header.name}
              snapshot={composerPreview}
              showChart={isSideBySide}
            />
          ) : (
            <RecentRunsRail
              overview={data}
              serviceId={id}
              fitHeight={isSideBySide ? testsColumnHeight : null}
            />
          )}
        </Grid.Col>
      </Grid>
    </div>
  );
}

// ---------------------------------------------------------------- attention

/**
 * What Vortex established and now doubts.
 *
 * <p>Never "this service is incomplete" — the readiness pill says that, once, where it belongs. Only
 * two things reach here, and both are claims about evidence rather than about setup, which is why
 * they stay full-width alerts rather than folding into the fact grid below.
 */
function Attention({ overview, serviceId }: { overview: Overview; serviceId: string }) {
  const items: { what: string; why: string; label: string | null; href: string | null }[] = [];

  if (overview.evidencePredatesRelease && overview.releaseGapText) {
    items.push({
      what: 'This service has not been tested at its current release',
      why: overview.releaseGapText,
      // No action button — the Tests section this used to link to is the one right below on this
      // same page now, not a separate destination to send somebody to.
      label: null,
      href: null,
    });
  }

  if (overview.capacity && !overview.capacity.quotable) {
    items.push({
      what: 'The evidence establishes no capacity boundary',
      why: overview.capacity.boundary,
      label: 'View evidence',
      href: `/services/${serviceId}/evidence`,
    });
  }

  if (items.length === 0) return null;

  return (
    <Stack gap="xs">
      {items.map((item) => (
        <Alert
          key={item.what}
          color="warn"
          icon={<IconAlertTriangle size={16} />}
          title={item.what}
        >
          <div className={classes.attention}>
            <Text size="sm">{item.why}</Text>
            {item.label && item.href && (
              <Button component="a" href={item.href} size="xs" variant="light" color="warn">
                {item.label}
              </Button>
            )}
          </div>
        </Alert>
      ))}
    </Stack>
  );
}

// ---------------------------------------------------------------- fact grid

/**
 * What Vortex knows, as instrumentation rather than a KPI band. Production stays the one figure
 * everything else is read against; beside it sits the evidence rail — this service's own most
 * recent reading for every test type Vortex supports, not just whichever test happened to run last.
 * Classification ("Isolated") already lives in the service header, so it is not repeated here.
 *
 * <p>Styled as the header's own continuation, not a section of its own: `.band` matches the header's
 * surface and horizontal inset, closes off the rounded corners the header opened, and cancels
 * `ServiceLayout`'s normal inter-section gap so the two sit flush — one quiet "service context" band
 * rather than a header followed by a second, identically-important-looking block.
 */
function FactGrid({
  overview,
  serviceId,
  onSelectTest,
}: {
  overview: Overview;
  serviceId: string;
  onSelectTest: (name: string) => void;
}) {
  return (
    <div className={classes.band}>
      <div className={classes.factGrid}>
        <ProductionItem overview={overview} serviceId={serviceId} />
        <div className={classes.evidenceRailWrap}>
          <EvidenceStrip evidence={overview.evidenceByTestType} onSelect={onSelectTest} />
        </div>
      </div>
      {/* Objectives shrink to a hint rather than a tile of their own once evidence is read per test
          type — each cell already shows what its own run made of them. The one thing still worth
          surfacing at band level is the case nothing has: a service with no objectives configured at
          all, where the domain's own call to action still deserves a place. */}
      {overview.objectives.length === 0 && <NoObjectivesHint serviceId={serviceId} />}
    </div>
  );
}

function NoObjectivesHint({ serviceId }: { serviceId: string }) {
  return (
    <div className={classes.objectivesHint}>
      <Text size="xs" c="dimmed">
        No objectives configured — a test still runs, but its result is{' '}
        <UnknownInline>Not evaluated</UnknownInline>, never a pass.
      </Text>
      <Button
        component="a"
        href={`/services/${serviceId}/configuration#objectives`}
        size="xs"
        variant="default"
      >
        Set them
      </Button>
    </div>
  );
}

function FactItem({
  label,
  value,
  source,
  info,
}: {
  label: string;
  value: ReactNode;
  /** Which test this figure belongs to — quiet, but always on, never behind the ⓘ. */
  source?: string;
  info?: ReactNode;
}) {
  return (
    <div className={classes.fact}>
      <div className={classes.factValueRow}>
        <span className={classes.factValue}>{value}</span>
        {info}
      </div>
      <div className={classes.factLabel}>{label}</div>
      {source && <div className={classes.factSource}>{source}</div>}
    </div>
  );
}

function ProductionItem({ overview, serviceId }: { overview: Overview; serviceId: string }) {
  const { production } = overview;

  if (!production) {
    return (
      <FactItem
        label="Production peak"
        value={<UnknownInline>Not recorded</UnknownInline>}
        info={
          <InfoPopover icon ariaLabel="Why production traffic matters" width={280}>
            <Text size="xs">
              Without it a test's load is a number somebody chose. Vortex can still run one — it
              just will not describe the result as production-validated.
            </Text>
            <Button
              component="a"
              href={`/services/${serviceId}/configuration#production`}
              size="xs"
              variant="default"
              mt="xs"
            >
              Record it
            </Button>
          </InfoPopover>
        }
      />
    );
  }

  return (
    <FactItem
      label="Production peak"
      value={shortRate(production.peakRate)}
      info={
        <InfoPopover icon ariaLabel="Production traffic detail" width={280}>
          <Text size="xs" fw={600} mb={2}>
            Production traffic
          </Text>
          <Text size="xs">Peak: {production.peakRate}</Text>
          {production.averageRate && <Text size="xs">Average: {production.averageRate}</Text>}
          <Text size="xs" c="dimmed" mt={4}>
            {/* Provenance, never dressed up. A figure somebody typed must not read like telemetry. */}
            {production.fetched
              ? production.source
              : production.attributed
                ? `${production.source} · entered by hand`
                : 'Entered by hand'}
            {production.observedWindow && ` · ${production.observedWindow}`}
          </Text>
        </InfoPopover>
      }
    />
  );
}

// ---------------------------------------------------------------- tests

/** 0 for a test that has never run — oldest possible, never "just as recent as right now". */
function lastRunMillis(test: Overview['tests'][number]): number {
  return test.latestRun ? new Date(test.latestRun.isoTimestamp).getTime() : 0;
}

function TestsSection({
  overview,
  serviceId,
  selectedName,
  onSelect,
  composerState,
  onCreateTest,
  onEditTest,
  onCloseComposer,
  onPreviewChange,
  showInlineChart,
}: {
  overview: Overview;
  serviceId: string;
  selectedName: string | null;
  onSelect: (name: string) => void;
  composerState: ComposerState;
  onCreateTest: () => void;
  onEditTest: (name: string) => void;
  onCloseComposer: () => void;
  onPreviewChange: (snapshot: ComposerPreviewSnapshot | null) => void;
  showInlineChart: boolean;
}) {
  const defaultEnvironment = overview.header.target?.environmentName ?? null;
  const blockedCount = overview.tests.filter((test) => !test.runnable).length;
  const runningName = overview.header.running?.testName ?? null;
  // Most recently run first — the same "what's active" ordering the Home page's own service
  // shelf uses (see `recencyMillis` in workbenchState.ts). A test that has never run sorts last:
  // it has no run to be recent about, not a claim that it's older than one that ran long ago.
  // A test that is running right now always leads, ahead of even its own past runs — it's what
  // the eye should find, and `motion.article`'s `layout` prop on each row is what makes that
  // reordering read as motion instead of a jump cut.
  const sortedTests = useMemo(
    () =>
      [...overview.tests].sort((a, b) => {
        if (a.name === runningName) return -1;
        if (b.name === runningName) return 1;
        return lastRunMillis(b) - lastRunMillis(a);
      }),
    [overview.tests, runningName],
  );

  return (
    <section>
      <div className={classes.sectionHead}>
        <div className={classes.sectionTitle}>
          <Title order={2} className={classes.sectionHeading}>
            {composerState.mode === 'closed' ? 'Tests' : 'Compose'}
          </Title>
          {composerState.mode === 'closed' && overview.tests.length > 0 && (
            <span className={classes.sectionCount}>{overview.tests.length}</span>
          )}
        </div>
        {/* No Cancel here while composing — TestComposer's own action row, right beside
            Create/Save, is the one place that control lives; a second one up here duplicated it. */}
        {/* And none here when the list is empty: the empty state below already offers exactly this
            action, with the sentence saying what a test is attached to it. Two identical calls to
            action a hand's width apart is one decision presented twice. */}
        {composerState.mode === 'closed' && overview.tests.length > 0 && (
          <Button
            onClick={onCreateTest}
            size="xs"
            variant="default"
            leftSection={<IconPlus size={14} />}
          >
            Create test
          </Button>
        )}
      </div>

      {composerState.mode !== 'closed' ? (
        <TestComposer
          serviceId={serviceId}
          mode={composerState.mode}
          editingName={composerState.mode === 'edit' ? composerState.name : undefined}
          onClose={onCloseComposer}
          onPreviewChange={onPreviewChange}
          showInlineChart={showInlineChart}
          target={overview.header.target}
        />
      ) : (
        <>
          {blockedCount > 0 && (
            <Text size="xs" c="dimmed" mb="sm">
              {blockedCount} of {overview.tests.length} cannot run right now. Each says why below.
            </Text>
          )}

          {overview.tests.length === 0 ? (
            <Stack gap={6} className={classes.emptyTests}>
              <Text size="sm" c="dimmed" maw="60ch">
                This service has no tests yet. A test is a configured thing you can execute: a load,
                spread across operations, held for a time.
              </Text>
              <Button onClick={onCreateTest} size="xs" variant="default" w="fit-content">
                Create a test
              </Button>
            </Stack>
          ) : (
            <div className={classes.tests}>
              {sortedTests.map((test) => (
                <TestRow
                  key={test.name}
                  serviceId={serviceId}
                  test={test}
                  production={overview.production}
                  defaultEnvironment={defaultEnvironment}
                  running={overview.header.running}
                  selected={test.name === selectedName}
                  onSelect={onSelect}
                  onEdit={onEditTest}
                />
              ))}
            </div>
          )}

          {/* The domain's own next step, offered only where ProjectReadiness says nothing has run.
              Not an invented to-do list. */}
          {overview.suggestSmokeTest && overview.tests.length > 0 && (
            <Text size="xs" c="dimmed" mt="md" maw="68ch">
              Nothing has run against this service yet. A smoke test — a very small load for a few
              seconds — confirms Vortex can reach it before you generate load that means anything.
            </Text>
          )}
        </>
      )}
    </section>
  );
}

