import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../test/renderWithProviders';
import type { Capacity, CapacityRange, RunSummary, TestRow } from '../../api/workspace';
import type { Run } from '../../api/run';
import { TestResult } from './TestResult';
import { phaseFourEvidence } from '../../test/phaseFourEvidence';

let runQueryResult: { data: Run | undefined } = { data: undefined };

vi.mock('../../api/run', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/run')>();
  return { ...actual, useRunQuery: () => runQueryResult };
});

beforeEach(() => {
  runQueryResult = { data: undefined };
});

// Recharts' ResponsiveContainer (used by the Soak timeline figure) measures its DOM node before
// drawing anything, and jsdom always reports 0x0 — without this, the timeline chart's own content,
// including any breakpoint marker, would never render regardless of what it was given.
beforeAll(() => {
  Object.defineProperty(HTMLElement.prototype, 'offsetWidth', { configurable: true, value: 600 });
  Object.defineProperty(HTMLElement.prototype, 'offsetHeight', { configurable: true, value: 200 });
  HTMLElement.prototype.getBoundingClientRect = () =>
    ({ width: 600, height: 200, top: 0, left: 0, bottom: 200, right: 600, x: 0, y: 0, toJSON() {} }) as DOMRect;
});

/**
 * One test's own result, rendered inline inside its row. No heading here repeats the test's
 * name — the row above it already says whose result this is — so these assert only what the
 * result itself adds: the run it came from, the verdict and its own answer, the figures, and
 * their gates.
 */

function aCapacity(overrides: Partial<Capacity> = {}): Capacity {
  return {
    compliantLevel: '50 requests/sec',
    label: 'Tested SLO-compliant capacity',
    boundary: '50 requests/sec compliant; no tested level failed',
    boundaryLabel: 'Tested capacity boundary',
    quotable: true,
    boundaryStatus: 'FAR_EDGE_NOT_REACHED',
    boundaryStatusLabel: 'far edge not reached',
    boundaryStrength: 'Low',
    firstNonCompliant: null,
    headroom: null,
    headroomRefusal:
      'This capacity was measured in an isolated test, where dependencies were simulated. '
      + 'Comparing it with production traffic would overstate the headroom the service actually has.',
    serviceVersion: '2.17.0',
    environmentName: 'local',
    classification: 'ISOLATED',
    dependencyMode: 'MOCKED',
    workloadName: 'capacity-check',
    operationMix: ['getOrder 100%'],
    objectives: ['p95 < 500 ms'],
    durationDisplay: '1m',
    measuredAt: '22 Aug 2026, 04:55',
    runId: 'exec-1',
    conditions: [
      'Isolated test, dependencies mocked',
      'Environment local',
      'Release 2.17.0',
      'Test capacity-check, 1m',
      'Measured 22 Aug 2026, 04:55',
    ],
    constraintCandidates: [],
    ...overrides,
  };
}

const RENDERABLE_RANGE: CapacityRange = {
  renderable: true,
  unit: 'requests/sec',
  markers: [
    { kind: 'TESTED_CAPACITY', label: 'Tested SLO-compliant capacity', displayWithUnit: '50 requests/sec', position: 0.5 },
  ],
  openEnded: true,
};

function aRun(overrides: Partial<RunSummary> = {}): RunSummary {
  return {
    id: 'exec-1',
    verdict: 'PASS',
    verdictLabel: 'Pass',
    stateLabel: 'Completed',
    terminal: true,
    testName: 'capacity-check',
    testType: 'AVERAGE_LOAD',
    testTypeLabel: 'Average load',
    levelDisplay: '50 requests/sec',
    environmentName: 'local',
    classification: 'ISOLATED',
    release: '2.17.0',
    answer: 'Objectives held at 50 requests/sec.',
    p95: '120 ms',
    durationDisplay: '1m',
    relativeTime: '57 minutes ago',
    isoTimestamp: '2026-08-22T04:55:00Z',
    matchesCurrentTest: true,
    differences: [],
    ...overrides,
  };
}

function anEvidence(overrides: Partial<Run['evidence']> = {}): NonNullable<Run['evidence']> {
  return {
    ...phaseFourEvidence(),
    identity: {
      executionId: 'exec-1',
      shortId: 'exec-1',
      serviceName: 'checkout-service',
      serviceVersion: '2.17.0',
      workloadName: 'capacity-check',
      testTypeLabel: 'Average load',
      environmentName: 'local',
      environmentTypeLabel: 'Local',
      classification: 'ISOLATED',
      classificationLabel: 'Isolated',
      targetUrl: 'https://checkout.internal',
      targetWasRewritten: false,
      targetRewriteReason: null,
      requestedAtIso: '2026-08-22T04:54:00Z',
      finishedAtDisplay: '22 Aug 2026, 04:55',
      durationDisplay: '1m',
    },
    verdict: {
      question: 'Does the service meet its objectives under the traffic it normally receives?',
      verdict: 'PASS',
      verdictLabel: 'Pass',
      answer: 'Objectives held at 50 requests/sec.',
      qualifications: [],
    },
    workload: {
      open: true,
      modelLabel: 'Arrival rate',
      modelGuidance: '',
      configuredPeakDisplay: '50 requests/sec',
      sourceDescribe: 'Entered by hand',
      achievedRateDisplay: '50 requests/sec',
      deliveredPercent: '100%',
      fellShort: false,
      deliveredCaveat: null,
      requestsDisplay: '3,000',
      estimatedRequestsDisplay: null,
      errorRateDisplay: '0%',
      failuresDisplay: '0',
      configuredDurationDisplay: '1m',
      actualDurationDisplay: '1m',
      operationMix: ['getOrder 100%'],
      scriptSourceLabel: 'Generated',
    },
    performance: {
      latencyRows: [],
      maxLatencyDisplay: null,
      hasLimitsCard: false,
      sloBreakpointDisplay: null,
      sloBreakpointStrengthLabel: null,
      sloBreakpointStagesText: null,
      systemSaturationDescribe: null,
      systemSaturationExplanation: null,
      headroomDisplay: null,
      headroomRefusal: null,
      baselineQuality: [],
    },
    acceptance: { hasObjectives: false, results: [], absenceExplanation: null },
    hasOperationBreakdown: false,
    operations: [],
    loadAxis: {
      renderable: false,
      svg: null,
      drawsBoundary: false,
      drawsSaturation: false,
      highestCompliantDisplay: null,
      firstNonCompliantDisplay: null,
      boundaryStatement: null,
      saturationDescribe: null,
      testedToDisplay: null,
    },
    timeline: { present: false, plots: [], stages: [], showsDerivedCaveat: false, tableRows: [], breakpointAtIso: null, levelChangeAtIso: null },
    observability: { present: false, signals: [], providersConsulted: [], gaps: [] },
    hasFindings: false,
    findings: [],
    comparison: null,
    provenance: {
      vortexVersion: '1.0.0',
      engineVersion: 'k6 0.50',
      runtimeVersion: 'Java 25',
      dockerImage: null,
      configurationHash: 'abc123',
      secretReferences: [],
      artifactDirectory: '/tmp/exec-1',
      reproductionCommand: 'vortex run capacity-check',
      hasArtifacts: false,
      artifactNames: [],
    },
    releaseMoved: false,
    previousCompatibleExecutionId: null,
    ...overrides,
  };
}

function aRunQueryResult(evidenceOverrides: Partial<Run['evidence']>): { data: Run } {
  return {
    data: {
      executionId: 'exec-1',
      running: false,
      terminal: true,
      stateLabel: 'Completed',
      plan: {
        projectId: 'checkout',
        projectName: 'checkout-service',
        testTypeLabel: 'Average load',
        testTypeQuestion: 'Does the service meet its objectives under normal traffic?',
        workloadName: 'capacity-check',
        environmentName: 'local',
        targetDisplay: 'https://checkout.internal',
        environmentTypeLabel: 'Local',
        workloadModelLabel: 'Arrival rate',
        peakLevelDisplay: '50 requests/sec',
        singleOperation: false,
        operationsSummary: 'getOrder',
        classification: 'ISOLATED',
        classificationLabel: 'Isolated',
        classificationCaveat: '',
        totalDurationDisplay: '1m',
      },
      progress: null,
      requestedAtDisplay: '22 Aug 2026, 04:54',
      startedAtDisplay: '22 Aug 2026, 04:54',
      failed: false,
      failureLabel: null,
      failureGuidance: null,
      failureDetail: null,
      cancelled: false,
      evidence: anEvidence(evidenceOverrides),
    },
  };
}

function aTest(overrides: Partial<TestRow> = {}): TestRow {
  return {
    name: 'capacity-check',
    description: null,
    question: 'Does the service meet its objectives under normal traffic?',
    testType: 'AVERAGE_LOAD',
    testTypeLabel: 'Average load',
    testTypeQuestion: 'Does the service meet its objectives under normal traffic?',
    saturating: false,
    model: 'OPEN',
    modelLabel: 'Arrival rate',
    levelDisplay: '50 requests/sec',
    levelUnit: 'requests/sec',
    durationDisplay: '1m',
    stageCount: 1,
    ramping: false,
    operationCount: 4,
    source: {
      kind: 'MANUAL',
      label: 'Manually entered',
      describe: 'Manually entered',
      detail: null,
      productionInformed: false,
      observedWindow: null,
      derivation: null,
    },
    versusProduction: null,
    runnable: true,
    problems: [],
    environmentName: 'local',
    latestRun: aRun(),
    runCount: 12,
    drift: null,
    composition: [],
    compositionDrift: null,
    capacity: aCapacity(),
    range: RENDERABLE_RANGE,
    ...overrides,
  };
}

describe('a test\'s inline result', () => {
  it('shows a provenance line with the run count and recency, not the test\'s name again', () => {
    renderWithProviders(<TestResult test={aTest()} production={null} />);

    // The row above already names the test — repeating it here said "capacity-check" twice in the
    // same glance.
    expect(screen.getByText('Run #12 · 57m')).toBeInTheDocument();
    expect(screen.queryByText(/capacity-check · Run/)).not.toBeInTheDocument();
  });

  it('offers a "no result yet" message when the test has never run', () => {
    renderWithProviders(<TestResult test={aTest({ latestRun: null })} production={null} />);

    expect(screen.getByText(/No result yet/)).toBeInTheDocument();
    expect(screen.queryByText('Result')).not.toBeInTheDocument();
  });

  it('notes when this run did not establish a tested-capacity boundary, but still states its verdict', () => {
    renderWithProviders(
      <TestResult
        test={aTest({
          capacity: null,
          range: { renderable: false, unit: null, markers: [], openEnded: false },
        })}
        production={null}
      />,
    );

    expect(
      screen.getByText('This run did not establish a tested-capacity boundary for capacity-check.'),
    ).toBeInTheDocument();
    // The run still has its own verdict and answer, independent of whether it established capacity.
    expect(screen.getByText('Pass')).toBeInTheDocument();
    expect(screen.getByText('Objectives held at 50 requests/sec.')).toBeInTheDocument();
  });

  it('always states this run\'s own verdict and answer, regardless of which run produced this test\'s capacity', () => {
    renderWithProviders(
      <TestResult
        test={aTest({
          capacity: aCapacity({ runId: 'exec-1' }),
          latestRun: aRun({ id: 'run-99', verdict: 'FAIL', verdictLabel: 'Fail', answer: 'Objectives were not met.' }),
        })}
        production={null}
      />,
    );

    // The verdict and answer come from this test's own latest run (run-99), never reattributed to
    // whichever run happened to produce the capacity figure (exec-1) shown alongside it.
    expect(screen.getByText('Fail')).toBeInTheDocument();
    expect(screen.getByText('Objectives were not met.')).toBeInTheDocument();
  });

  it('pairs the verdict badge with its own narrative answer, the same pairing the full report opens with', () => {
    renderWithProviders(
      <TestResult
        test={aTest({ latestRun: aRun({ verdict: 'PASS', verdictLabel: 'Pass', answer: 'Objectives held at 50 requests/sec.' }) })}
        production={null}
      />,
    );

    expect(screen.getByText('Pass')).toBeInTheDocument();
    expect(screen.getByText('Objectives held at 50 requests/sec.')).toBeInTheDocument();
  });

  it('states the headroom refusal without any click, and its full reason after "Why?"', async () => {
    renderWithProviders(<TestResult test={aTest()} production={null} />);

    expect(screen.getByText('Not established')).toBeInTheDocument();
    expect(
      screen.queryByText(/This capacity was measured in an isolated test/),
    ).not.toBeInTheDocument();

    await userEvent.click(
      screen.getByRole('button', { name: 'Why headroom is not established' }),
    );

    expect(
      await screen.findByText(/This capacity was measured in an isolated test/),
    ).toBeInTheDocument();
  });

  it('states p95 latency and duration as quick metrics beside the graph, before the full evidence resolves', () => {
    renderWithProviders(<TestResult test={aTest()} production={null} />);

    expect(screen.getByText('120 ms')).toBeInTheDocument();
    expect(screen.getByText('p95 latency')).toBeInTheDocument();
  });

  it('replaces the single p95 figure with the full latency breakdown once the run\'s evidence resolves', () => {
    runQueryResult = aRunQueryResult({
      performance: {
        latencyRows: [
          { percentileLabel: 'p50', durationDisplay: '38 ms' },
          { percentileLabel: 'p95', durationDisplay: '120 ms' },
          { percentileLabel: 'p99', durationDisplay: '180 ms' },
        ],
        maxLatencyDisplay: '210 ms',
        hasLimitsCard: false,
        sloBreakpointDisplay: null,
        sloBreakpointStrengthLabel: null,
        sloBreakpointStagesText: null,
        systemSaturationDescribe: null,
        systemSaturationExplanation: null,
        headroomDisplay: null,
        headroomRefusal: null,
        baselineQuality: [],
      },
    });

    renderWithProviders(<TestResult test={aTest()} production={null} />);

    expect(screen.getByText('p50')).toBeInTheDocument();
    expect(screen.getByText('38 ms')).toBeInTheDocument();
    expect(screen.getByText('p99')).toBeInTheDocument();
    expect(screen.getByText('180 ms')).toBeInTheDocument();
    // The single p95 metric label is gone — the full breakdown replaces it rather than sitting
    // alongside it, so p95 itself is now only stated once, as one row of that breakdown.
    expect(screen.queryByText('p95 latency')).not.toBeInTheDocument();
  });

  it('lists each objective\'s own verdict and observed value once the run\'s evidence resolves', () => {
    runQueryResult = aRunQueryResult({
      acceptance: {
        hasObjectives: true,
        results: [
          {
            describe: 'p95 latency below 200 ms',
            verdict: 'PASS',
            verdictLabel: 'Pass',
            observed: '48 ms',
            note: null,
            kind: 'LATENCY',
          },
          {
            describe: 'error rate below 5%',
            verdict: 'FAIL',
            verdictLabel: 'Fail',
            observed: '12%',
            note: 'exceeded during the ramp',
            kind: 'ERROR_RATE',
          },
        ],
        absenceExplanation: null,
      },
    });

    renderWithProviders(<TestResult test={aTest()} production={null} />);

    expect(screen.getByText('p95 latency below 200 ms')).toBeInTheDocument();
    expect(screen.getByText('48 ms')).toBeInTheDocument();
    expect(screen.getByText('error rate below 5%')).toBeInTheDocument();
    // The observed value and its note render as separate nodes within the same cell — matched by
    // the cell's full text content rather than one exact string.
    expect(
      screen.getByText(
        (_content, node) => node?.textContent === '12% — exceeded during the ramp',
      ),
    ).toBeInTheDocument();
  });

  it('does not repeat the evidence-conditions list — that belongs to the full report now', () => {
    renderWithProviders(<TestResult test={aTest()} production={null} />);

    expect(screen.queryByText(/evidence condition/)).not.toBeInTheDocument();
  });

  it('lays out its metrics as a genuine table, one column per figure', () => {
    renderWithProviders(<TestResult test={aTest()} production={null} />);

    const table = screen.getByRole('table');
    expect(within(table).getByRole('columnheader', { name: 'p95 latency' })).toBeInTheDocument();
    expect(within(table).getByRole('cell', { name: '120 ms' })).toBeInTheDocument();
  });

  it('uses the wide, labelled range figure for a Breakpoint test', () => {
    renderWithProviders(
      <TestResult
        test={aTest({
          name: 'breakpoint-check',
          testType: 'BREAKPOINT',
          testTypeLabel: 'Breakpoint',
          saturating: true,
          range: {
            renderable: true,
            unit: 'requests/sec',
            markers: [
              {
                kind: 'TESTED_CAPACITY',
                label: 'Tested SLO-compliant capacity',
                displayWithUnit: '100 requests/sec',
                position: 0.4,
              },
              {
                kind: 'FIRST_FAILING',
                label: 'First observed non-compliant load',
                displayWithUnit: '250 requests/sec',
                position: 1,
              },
            ],
            openEnded: false,
          },
        })}
        production={null}
      />,
    );

    expect(
      screen.getByRole('img', {
        name: /Tested SLO-compliant capacity 100 requests\/sec.*First observed non-compliant load 250 requests\/sec/,
      }),
    ).toBeInTheDocument();
  });

  it('shows the time-series view for a Soak test whose evidence carries a timeline, not the load-level scale', () => {
    runQueryResult = aRunQueryResult({
      timeline: {
        present: true,
        plots: [
          {
            label: 'Latency (p95)',
            hasData: true,
            unitSymbol: 'ms',
            points: [
              { atIso: '2026-08-22T04:54:00Z', value: 100 },
              { atIso: '2026-08-22T04:54:30Z', value: 150 },
            ],
            referencePoints: [],
            referenceLevel: null,
          },
        ],
        stages: [],
        showsDerivedCaveat: false,
        tableRows: [],
        breakpointAtIso: null,
        levelChangeAtIso: null,
      },
    });

    renderWithProviders(
      <TestResult test={aTest({ testType: 'SOAK', testTypeLabel: 'Soak' })} production={null} />,
    );

    expect(screen.getByText('Latency (p95)')).toBeInTheDocument();
    // The load-level scale's own short labels never render alongside the timeline view.
    expect(screen.queryByText('Tested')).not.toBeInTheDocument();
    expect(
      screen.queryByText('This run did not establish a tested-capacity boundary for capacity-check.'),
    ).not.toBeInTheDocument();
  });

  it('marks the shared breakpoint instant once, by name, across a Soak test\'s stacked charts', () => {
    runQueryResult = aRunQueryResult({
      timeline: {
        present: true,
        plots: [
          {
            label: 'Throughput',
            hasData: true,
            unitSymbol: 'requests/sec',
            points: [
              { atIso: '2026-08-22T04:54:00Z', value: 40 },
              { atIso: '2026-08-22T04:54:30Z', value: 120 },
              { atIso: '2026-08-22T04:55:00Z', value: 118 },
            ],
            referencePoints: [],
            referenceLevel: null,
          },
          {
            label: 'p95 Latency',
            hasData: true,
            unitSymbol: 'ms',
            points: [
              { atIso: '2026-08-22T04:54:00Z', value: 100 },
              { atIso: '2026-08-22T04:54:30Z', value: 180 },
              { atIso: '2026-08-22T04:55:00Z', value: 1900 },
            ],
            referencePoints: [],
            referenceLevel: 200,
          },
        ],
        stages: [],
        showsDerivedCaveat: false,
        tableRows: [],
        breakpointAtIso: '2026-08-22T04:54:30Z',
        levelChangeAtIso: null,
      },
    });

    renderWithProviders(
      <TestResult test={aTest({ testType: 'SOAK', testTypeLabel: 'Soak' })} production={null} />,
    );

    // Each chart gets a proper heading, not a caption pretending to be one.
    expect(screen.getByText('Throughput')).toBeInTheDocument();
    expect(screen.getByText('p95 Latency')).toBeInTheDocument();
    // The breakpoint is named once for the whole figure, not repeated per chart.
    expect(screen.getAllByText('First objective violation')).toHaveLength(1);
  });

  it('falls back to the load-level scale for a Soak test whose evidence has no timeline yet', () => {
    renderWithProviders(
      <TestResult test={aTest({ testType: 'SOAK', testTypeLabel: 'Soak' })} production={null} />,
    );

    // runQueryResult defaults to no evidence resolved yet, so `evidence?.timeline.present` is
    // falsy — same range-based instrument as any other non-saturating test, unchanged.
    expect(screen.getByText('Tested')).toBeInTheDocument();
  });

  it('shows the time-series view for a Smoke test too, even one whose workload ended up ramping', () => {
    // A Smoke test's own question — is the workload valid and the service reachable — has no real
    // position on a load-level scale, whether or not this particular run happened to establish one.
    runQueryResult = aRunQueryResult({
      timeline: {
        present: true,
        plots: [
          {
            label: 'Throughput',
            hasData: true,
            unitSymbol: 'requests/sec',
            points: [
              { atIso: '2026-08-22T04:54:00Z', value: 35 },
              { atIso: '2026-08-22T04:54:30Z', value: 120 },
            ],
            referencePoints: [],
            referenceLevel: null,
          },
        ],
        stages: [],
        showsDerivedCaveat: false,
        tableRows: [],
        breakpointAtIso: null,
        levelChangeAtIso: null,
      },
    });

    renderWithProviders(
      <TestResult
        test={aTest({ testType: 'SMOKE', testTypeLabel: 'Smoke', saturating: false })}
        production={null}
      />,
    );

    expect(screen.getByText('Throughput')).toBeInTheDocument();
    expect(screen.queryByText('Tested')).not.toBeInTheDocument();
  });

  it('gives Average load the comparison summary, never a scale or a chart, even with a timeline present', () => {
    // Average load's own question is "does it meet objectives under normal traffic" — a comparison
    // against one known reference, not a boundary hunt and not a metric-over-time story. This is
    // deliberately narrow: the exception is a mapping keyed by kind (see testVisualization.ts), not
    // "any non-saturating test with timeline data."
    runQueryResult = aRunQueryResult({
      timeline: {
        present: true,
        plots: [
          {
            label: 'Throughput',
            hasData: true,
            unitSymbol: 'requests/sec',
            points: [
              { atIso: '2026-08-22T04:54:00Z', value: 35 },
              { atIso: '2026-08-22T04:54:30Z', value: 50 },
            ],
            referencePoints: [],
            referenceLevel: null,
          },
        ],
        stages: [],
        showsDerivedCaveat: false,
        tableRows: [],
        breakpointAtIso: null,
        levelChangeAtIso: null,
      },
    });

    renderWithProviders(
      <TestResult
        test={aTest({ testType: 'AVERAGE_LOAD', testTypeLabel: 'Average load' })}
        production={null}
      />,
    );

    expect(screen.getByText('Tested level')).toBeInTheDocument();
    expect(screen.queryByText('Throughput')).not.toBeInTheDocument();
    expect(screen.queryByText('Tested')).not.toBeInTheDocument();
  });

  it('adds the stage ladder to a Stress test\'s range figure — pressure progressing through stages', () => {
    runQueryResult = aRunQueryResult({
      timeline: {
        present: true,
        plots: [],
        stages: [
          {
            levelDisplay: '100 requests/sec',
            achievedDisplay: '100 requests/sec',
            p95Display: '80 ms',
            errorRateDisplay: '0%',
            resultKind: 'met',
            violatedThresholds: [],
            signals: [],
            basisLabel: 'measured',
          },
          {
            levelDisplay: '250 requests/sec',
            achievedDisplay: '230 requests/sec',
            p95Display: '900 ms',
            errorRateDisplay: '12%',
            resultKind: 'violated',
            violatedThresholds: ['p95 latency below 500 ms'],
            signals: [],
            basisLabel: 'measured',
          },
        ],
        showsDerivedCaveat: false,
        tableRows: [],
        breakpointAtIso: null,
        levelChangeAtIso: null,
      },
    });

    renderWithProviders(
      <TestResult
        test={aTest({
          testType: 'STRESS',
          testTypeLabel: 'Stress',
          saturating: true,
          range: {
            renderable: true,
            unit: 'requests/sec',
            markers: [
              { kind: 'TESTED_CAPACITY', label: 'Tested SLO-compliant capacity', displayWithUnit: '100 requests/sec', position: 0.4 },
              { kind: 'FIRST_FAILING', label: 'First observed non-compliant load', displayWithUnit: '250 requests/sec', position: 1 },
            ],
            openEnded: false,
          },
        })}
        production={null}
      />,
    );

    // The wide range figure still draws (the boundary is still real evidence)...
    expect(
      screen.getByRole('img', { name: /Tested SLO-compliant capacity 100 requests\/sec/ }),
    ).toBeInTheDocument();
    // ...and the stage-by-stage progression sits beneath it, as a table of real measurements
    // rather than a shaded gradient CapacityRange itself refuses to draw.
    expect(screen.getByText('230 requests/sec')).toBeInTheDocument();
    expect(screen.getByText('900 ms')).toBeInTheDocument();
  });

  it('gives Spike the time-series primitive, annotated at the jump, with the magnitude reference folded into a caption', () => {
    runQueryResult = aRunQueryResult({
      timeline: {
        present: true,
        plots: [
          {
            label: 'Throughput',
            hasData: true,
            unitSymbol: 'requests/sec',
            points: [
              { atIso: '2026-08-22T04:54:00Z', value: 35 },
              { atIso: '2026-08-22T04:54:30Z', value: 250 },
            ],
            referencePoints: [],
            referenceLevel: null,
          },
        ],
        stages: [],
        showsDerivedCaveat: false,
        tableRows: [],
        breakpointAtIso: null,
        levelChangeAtIso: '2026-08-22T04:54:15Z',
      },
    });

    renderWithProviders(
      <TestResult
        test={aTest({
          testType: 'SPIKE',
          testTypeLabel: 'Spike',
          saturating: true,
          range: {
            renderable: true,
            unit: 'requests/sec',
            markers: [
              { kind: 'PRODUCTION', label: 'Production peak', displayWithUnit: '35 requests/sec', position: 0.14 },
              { kind: 'TESTED_CAPACITY', label: 'Tested SLO-compliant capacity', displayWithUnit: '250 requests/sec', position: 1 },
            ],
            openEnded: true,
          },
        })}
        production={null}
      />,
    );

    // Temporal, not a scale — the jump is named, never "First objective violation" (that name is
    // reserved for a compliance breakpoint, which this run never had).
    expect(screen.getByText('Throughput')).toBeInTheDocument();
    expect(screen.getByText('Traffic jump')).toBeInTheDocument();
    expect(screen.queryByText('First objective violation')).not.toBeInTheDocument();
    // The magnitude fact is stated once, in words, rather than reintroducing the dot-track figure.
    expect(screen.getByText(/Peaked at 250 requests\/sec.*production peak of 35 requests\/sec/)).toBeInTheDocument();
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });
});
