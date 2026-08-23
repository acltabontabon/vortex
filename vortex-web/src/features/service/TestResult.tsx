import type { ReactNode } from 'react';
import { Text } from '@mantine/core';
import type { Production, Verdict, TestRow as Test } from '../../api/workspace';
import type { ResourceSignal, RunEvidence } from '../../api/run';
import { useRunQuery } from '../../api/run';
import { UnknownInline } from '../../components/Unknown';
import { InfoPopover } from '../../components/InfoPopover';
import { VerdictBadge } from '../../components/VerdictBadge';
import { EvidenceScale } from '../../components/charts/EvidenceScale';
import { CapacityRangeFigure } from '../../components/charts/CapacityRangeFigure';
import { TimeSeriesFigure } from '../../components/charts/TimeSeriesFigure';
import { StageLadder } from '../../components/charts/StageLadder';
import { LoadSummary } from '../../components/charts/LoadSummary';
import { UtilizationBar } from '../../components/charts/UtilizationBar';
import { chooseVisualization, rangeReferenceCaption, type VisualizationPlan } from '../../lib/testVisualization';
import { shortRelativeTime } from '../../lib/testState';
import classes from './TestResult.module.css';

/**
 * One test's own result — the gist, not the case file. Nothing here belongs to a different test.
 *
 * <p>Lives inline inside {@link TestRow}'s own expandable region, directly under the row that owns
 * it, rather than in a separate section elsewhere on the page. Proximity is the provenance: there is
 * no heading here repeating the test's name, because the row above it already said whose result this
 * is — the only new thing this component states is the run and how long ago it happened.
 *
 * <p>Leads with the same verdict-and-answer pairing the full report opens with — a badge and one
 * sentence, always present once a run exists, regardless of whether that run went on to establish a
 * capacity boundary. Below that sits the one graph available at this level when a run did establish
 * one (the production-vs-tested comparison, or for a saturating test, the wider range figure naming
 * both edges), then the numbers that give it context and, per objective, whether it was met — the
 * same facts a person would otherwise open the full report to see first. Everything else a run
 * produced — the timeline, the per-operation breakdown, findings, provenance — stays real evidence,
 * but stays on the full result at `/runs/:id`, one click away via "View full result", not repeated
 * here in miniature.
 *
 * <p>`test.latestRun`/`test.capacity`/`test.range` come from Overview's own payload, so the verdict,
 * answer, graph and headroom are on screen the instant this expands — no request in between. The
 * latency breakdown and per-objective results are not in that payload (Overview computes one row per
 * test, not a full evidence document per test), so those two are fetched once this run's own full
 * evidence is needed — the same `useRunQuery` the full report itself uses, so opening that report
 * afterwards is an instant cache hit rather than a second fetch. `Collapse`'s `keepMounted={false}` in
 * {@link TestRow} is what makes "once" mean once per expand, not once per page load: the query only
 * runs while a row is actually open, and the single `run.p95` Overview already sent stands in for a
 * moment before it resolves.
 *
 * <p>Which instrument draws below the answer is decided by `chooseVisualization()` in
 * `testVisualization.ts` — a `TestType` maps to a shape of question there, once, and that shape maps
 * to one of a small set of reusable primitives ({@link CapacityRangeFigure}, {@link TimeSeriesFigure},
 * {@link LoadSummary}, {@link EvidenceScale}). Nothing here re-decides that by kind; this component
 * only turns the returned plan into JSX.
 */
export function TestResult({ test, production }: { test: Test; production: Production | null }) {
  // Called before the "never run" early return below so this hook always runs in the same order —
  // `enabled: executionId !== null` (see useRunQuery) is what actually skips the fetch in that case,
  // not conditionally calling the hook, which React's rules of hooks forbid.
  const evidence = useRunQuery(test.latestRun?.id ?? null).data?.evidence;

  if (!test.latestRun) {
    return (
      <Text size="sm" c="dimmed" className={classes.neverRun}>
        No result yet — run {test.name} to establish the first one.
      </Text>
    );
  }

  const run = test.latestRun;
  const plan = chooseVisualization({
    testType: test.testType,
    hasTimeline: Boolean(evidence?.timeline.present),
    hasRange: test.range.renderable,
  });

  return (
    <div className={classes.result}>
      <div className={classes.head}>
        <span className={classes.heading}>Result</span>
        {/* Not the test's name — the row above already says whose result this is, one line up. */}
        <span className={classes.provenance}>
          Run #{test.runCount} · {shortRelativeTime(run.relativeTime)}
        </span>
        <a className={classes.viewFull} href={`/runs/${run.id}`}>
          View full result →
        </a>
      </div>

      <div className={classes.answerRow}>
        <VerdictBadge verdict={run.verdict} label={run.verdictLabel} size="lg" />
        <Text size="sm" className={classes.answer}>
          {run.answer}
        </Text>
      </div>

      <div className={classes.instrument}>
        <Instrument plan={plan} test={test} production={production} evidence={evidence} />

        {/* Side by side rather than stacked: both are naturally narrow blocks, and a Tests column
            wide enough for the graph above has plenty of width neither needed on its own. */}
        <div className={classes.details}>
          <MetricsTable
            test={test}
            run={run}
            evidence={evidence}
            hideHeadroom={plan.primitive === 'load-summary'}
          />

          {/* Not for `load-summary` — `LoadSummary` above already renders this exact same list (the
              same verdict, describe and observed value per objective) as its own "Latency
              objective"/"Error rate objective" facts. A second table repeating it here said nothing
              new, just louder. Every other instrument stays silent on per-objective results, so this
              table remains their only place to see it. */}
          {plan.primitive !== 'load-summary'
            && evidence && evidence.acceptance.hasObjectives && evidence.acceptance.results.length > 0 && (
            <div className={classes.objectivesCol}>
              <table className={classes.objectives}>
                <colgroup>
                  <col className={classes.objectiveVerdictCol} />
                  <col className={classes.objectiveDescribeCol} />
                  <col className={classes.objectiveObservedCol} />
                </colgroup>
                <thead>
                  <tr>
                    <th></th>
                    <th>Objective</th>
                    <th>Observed</th>
                  </tr>
                </thead>
                <tbody>
                  {evidence.acceptance.results.map((result) => (
                    <tr key={result.describe}>
                      <td>
                        <VerdictBadge verdict={result.verdict as Verdict} label={result.verdictLabel} subtleText />
                      </td>
                      <td>{result.describe}</td>
                      <td>
                        {result.observed}
                        {result.note && <span className={classes.objectiveNote}> — {result.note}</span>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        {/* The service's own CPU/memory — full width, below the metrics/objectives
            pairing rather than a third column beside them, since it answers a different question
            ("did it run out of something?") than either. Absent entirely, not an empty state, when
            this run observed none: a test row with nothing to say about resources should look
            exactly like one that never asked, not like a placeholder waiting to fill in. */}
        {evidence && evidence.resources.service.length > 0 && (
          <ServiceResources signals={evidence.resources.service} />
        )}
      </div>
    </div>
  );
}

/**
 * The service's own CPU/memory for this run — never the load generator's. `evidence.resources`
 * carries three scopes (service, generator, generatorHost); this draws only `service`, the one
 * scope this row's test can be judged against. The other two stay on the full report, one click
 * away, so this block can't be misread as "the load generator kept up" when it's actually silent
 * on that question.
 */
function ServiceResources({ signals }: { signals: ResourceSignal[] }) {
  return (
    <div className={classes.resources}>
      <Text size="xs" c="dimmed" tt="uppercase" fw={600} className={classes.resourcesHeading}>
        Service resources
      </Text>
      <div className={classes.resourceRows}>
        {signals.map((signal) => {
          // CPU is reported as a fraction of one core (Docker's own convention — "0.5" means half
          // a core), which reads as a bare, unitless number without this. Every other kind's
          // display already carries its own unit (MB, etc.), so this is CPU-only, appended once
          // rather than to both the value and the limit.
          const unit = signal.kind === 'CPU' ? ' cores' : '';
          return (
            <div key={signal.id} className={classes.resourceRow}>
              <div className={classes.resourceName}>
                <Text size="sm">{signal.name}</Text>
                <Text size="xs" c="dimmed">
                  {signal.kindLabel}
                </Text>
              </div>
              <Text size="sm" className={classes.resourceValue}>
                {signal.display}
                {signal.limitDisplay && <span className={classes.dim}> / {signal.limitDisplay}</span>}
                {unit && <span className={classes.dim}>{unit}</span>}
              </Text>
              <UtilizationBar fraction={signal.utilisationFraction} atLimit={signal.atItsLimit} />
              <Text size="xs" className={signal.atItsLimit ? classes.fail : classes.dim}>
                {signal.utilisationDisplay
                  ? `${signal.utilisationDisplay} of limit${signal.atItsLimit ? ' — at limit' : ''}`
                  : 'no limit published'}
              </Text>
            </div>
          );
        })}
      </div>
    </div>
  );
}

/**
 * Turns a `VisualizationPlan` into the one instrument this test's result actually shows. The only
 * place `plan.primitive` is switched on — everywhere else in this file, and in every chart
 * component it renders, stays plan-agnostic.
 */
function Instrument({
  plan,
  test,
  production,
  evidence,
}: {
  plan: VisualizationPlan;
  test: Test;
  production: Production | null;
  evidence: RunEvidence | null | undefined;
}) {
  switch (plan.primitive) {
    case 'range-wide':
      return (
        <div className={classes.saturatingFigure}>
          <CapacityRangeFigure
            range={test.range}
            size="wide"
            emphasize={plan.emphasis === 'breakpoint' ? 'FIRST_FAILING' : undefined}
          />
          {plan.showStageLadder && evidence?.timeline.present && (
            <StageLadder stages={evidence.timeline.stages} />
          )}
        </div>
      );

    case 'time-series':
      // Guaranteed by `chooseVisualization` (it only returns this plan when a timeline is present),
      // but TypeScript can't see that link — the guard is defensive, never expected to trigger.
      if (!evidence?.timeline.present) return null;
      return (
        <div className={classes.timelineFigure}>
          <TimeSeriesFigure
            timeline={evidence.timeline}
            annotation={plan.annotation}
            secondaryReference={plan.annotation === 'jump' ? rangeReferenceCaption(test.range) : null}
          />
        </div>
      );

    case 'load-summary':
      return <LoadSummary test={test} evidence={evidence} />;

    case 'range-compact':
      return <EvidenceScale range={test.range} production={production} capacity={test.capacity} />;

    case 'unavailable':
      return (
        <Text size="sm" c="dimmed">
          This run did not establish a tested-capacity boundary for {test.name}.
        </Text>
      );
  }
}

/**
 * The handful of numbers that give the graph above its context — never a repeat of it, since the
 * graph already states the level and the comparison. A real `<table>` rather than a row of labelled
 * divs: this is tabular data (one column per figure, one row of values), and a table says so to
 * assistive tech the way a flex row of `<div>`s cannot.
 */
function MetricsTable({
  test,
  run,
  evidence,
  hideHeadroom,
}: {
  test: Test;
  run: NonNullable<Test['latestRun']>;
  evidence: RunEvidence | null | undefined;
  /** True when the instrument above already states headroom itself — `LoadSummary`, for Average
   *  load — so this table doesn't say the same figure twice. Every other kind's headroom lives only
   *  here, unchanged. */
  hideHeadroom?: boolean;
}) {
  const latency =
    evidence && evidence.performance.latencyRows.length > 0
      ? evidence.performance.latencyRows.map((row) => ({ label: row.percentileLabel, value: row.durationDisplay }))
      : run.p95
        ? [{ label: 'p95 latency', value: run.p95 as ReactNode }]
        : [];

  const columns: { label: string; value: ReactNode }[] = [
    ...latency,
    ...(run.durationDisplay ? [{ label: 'Duration', value: run.durationDisplay as ReactNode }] : []),
    ...(test.capacity && !hideHeadroom
      ? [
          {
            label: 'Production headroom',
            value: test.capacity.headroom ? (
              <>{test.capacity.headroom} above production</>
            ) : (
              <>
                <UnknownInline>Not established</UnknownInline>{' '}
                <InfoPopover icon ariaLabel="Why headroom is not established" width={340}>
                  <Text size="xs">{test.capacity.headroomRefusal}</Text>
                </InfoPopover>
              </>
            ),
          },
        ]
      : []),
  ];

  if (columns.length === 0) return null;

  return (
    <table className={classes.metrics}>
      <thead>
        <tr>
          {columns.map((column) => (
            <th key={column.label}>{column.label}</th>
          ))}
        </tr>
      </thead>
      <tbody>
        <tr>
          {columns.map((column) => (
            <td key={column.label}>{column.value}</td>
          ))}
        </tr>
      </tbody>
    </table>
  );
}
