import { describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../test/renderWithProviders';
import type {
  Capacity,
  Overview,
  Readiness,
  RunSummary,
  TestRow,
  TestTypeEvidence,
} from '../../api/workspace';
import { OverviewPage } from './OverviewPage';

// TestComposer's own fields/behavior are that component's own test file's job — Overview only
// needs to know the composer opened in the right place and can be closed. A real render depends on
// data hooks this file doesn't mock, which would otherwise leave every composing-mode test staring
// at a permanent loading skeleton with no Cancel button ever reaching the DOM.
vi.mock('./TestComposer', () => ({
  TestComposer: ({ onClose }: { onClose: () => void }) => (
    <button onClick={onClose}>Cancel</button>
  ),
}));

/**
 * Overview is a control surface, not a report: these assert that the state a reader needs is always
 * on the page, and that the explanation behind it is one click away rather than a second permanent
 * paragraph — the split the redesign exists to make.
 */

let queryResult: { data: Overview | undefined; isError: boolean } = {
  data: undefined,
  isError: false,
};

vi.mock('../../api/workspace', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/workspace')>();
  return { ...actual, useOverviewQuery: () => queryResult };
});

// Only exercised by the running-test case below, so a currently-running test's row can render its
// real RunningTestPanel — same mock shape RunPage.test.tsx uses for the same SSE hook.
vi.mock('../../api/run', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/run')>();
  return {
    ...actual,
    useCancelRunMutation: () => ({ mutate: vi.fn(), isPending: false }),
    // TestResult fetches a selected row's full evidence for its latency/objective breakdown — these
    // tests are about Overview's own layout and data, not that enrichment, so it stays unresolved.
    useRunQuery: () => ({ data: undefined }),
  };
});

vi.mock('../../api/runs', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/runs')>();
  return {
    ...actual,
    useRunProgress: (_id: string, options: { initialProgress?: unknown }) =>
      options.initialProgress ?? null,
  };
});

vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  return { ...actual, useParams: () => ({ id: 'checkout' }) };
});

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

function aRun(overrides: Partial<RunSummary> = {}): RunSummary {
  return {
    id: 'run-1',
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
    relativeTime: '44 minutes ago',
    isoTimestamp: '2026-08-22T04:55:00Z',
    matchesCurrentTest: true,
    differences: [],
    ...overrides,
  };
}

function anEvidence(overrides: Partial<TestTypeEvidence> = {}): TestTypeEvidence {
  return {
    testType: 'BREAKPOINT',
    testTypeLabel: 'Breakpoint',
    hasEvidence: false,
    outcome: null,
    outcomeLabel: null,
    primaryValueKind: null,
    primaryValue: null,
    secondaryValue: null,
    workloadName: null,
    environmentName: null,
    release: null,
    executionId: null,
    relativeTime: null,
    isoTimestamp: null,
    answer: null,
    running: false,
    runningWorkloadName: null,
    ...overrides,
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
    latestRun: null,
    runCount: 0,
    drift: null,
    composition: [],
    compositionDrift: null,
    capacity: null,
    range: { renderable: false, unit: null, markers: [], openEnded: false },
    ...overrides,
  };
}

function anOverview(overrides: Partial<Overview> = {}): Overview {
  return {
    header: {
      id: 'checkout',
      name: 'checkout-service',
      description: null,
      // A real target, because every fixture here asserts figures, tests or runs — an overview
      // with none would take the vortex branch instead of the page these are about.
      target: {
        environmentName: 'local',
        baseUrl: 'http://localhost:8080',
        environmentTypeLabel: 'Local',
        classification: 'ISOLATED',
        classificationLabel: 'Isolated',
        classificationCaveat:
          'Dependencies were simulated; this does not establish integrated behaviour.',
        dependencyModeLabel: 'Mocked',
        targetKind: 'EXTERNAL_ENDPOINT',
        targetSummary: 'http://localhost:8080',
      },
      environmentCount: 1,
      release: '2.17.0',
      readiness: {
        canRun: true,
        satisfiedCount: 7,
        totalCount: 7,
        blockerCount: 0,
        items: [],
        nextStepText: null,
      },
      operationCount: 4,
      testCount: 1,
      runCount: 1,
      running: null,
    },
    production: null,
    objectives: [],
    capacity: null,
    range: { renderable: false, unit: null, markers: [], openEnded: false },
    latestRun: null,
    tests: [],
    recentRuns: [],
    suggestSmokeTest: false,
    evidencePredatesRelease: false,
    releaseGapText: null,
    evidenceByTestType: [],
    ...overrides,
  };
}

describe('the overview page', () => {
  it('shows production peak beside this service\'s own evidence for every test type', () => {
    queryResult = {
      data: anOverview({
        production: {
          peakRate: '35 requests/sec',
          averageRate: '20 requests/sec',
          p95ObservedRate: null,
          source: 'Dynatrace',
          attributed: true,
          fetched: true,
          observedWindow: '1–7 Aug',
          note: null,
          qualityFacts: [],
          observedMix: [],
          mixCoverage: null,
        },
        objectives: ['p95 < 500 ms'],
        evidenceByTestType: [
          anEvidence({
            testType: 'BREAKPOINT',
            testTypeLabel: 'Breakpoint',
            hasEvidence: true,
            outcome: 'PASS',
            outcomeLabel: 'Pass',
            primaryValueKind: 'RATE',
            primaryValue: '50 requests/sec',
            relativeTime: '44 minutes ago',
          }),
        ],
      }),
      isError: false,
    };
    renderWithProviders(<OverviewPage />);

    expect(screen.getAllByText('35 req/s').length).toBeGreaterThan(0);
    expect(screen.getByText('50 req/s')).toBeInTheDocument();
    expect(screen.getByText('Breakpoint')).toBeInTheDocument();
  });

  it('keeps elaboration behind the ⓘ trigger rather than permanently under the value', async () => {
    queryResult = {
      data: anOverview({
        production: {
          peakRate: '35 requests/sec',
          averageRate: '20 requests/sec',
          p95ObservedRate: null,
          source: 'Dynatrace',
          attributed: true,
          fetched: true,
          observedWindow: '1–7 Aug',
          note: null,
          qualityFacts: [],
          observedMix: [],
          mixCoverage: null,
        },
      }),
      isError: false,
    };
    renderWithProviders(<OverviewPage />);

    expect(screen.queryByText('20 requests/sec average')).not.toBeInTheDocument();
    expect(screen.queryByText(/Average: 20 requests\/sec/)).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Production traffic detail' }));

    expect(await screen.findByText(/Average: 20 requests\/sec/)).toBeInTheDocument();
  });

  it('never renders a standalone "Latest run" fact — that state lives in Recent runs', () => {
    queryResult = {
      data: anOverview({
        capacity: aCapacity(),
        latestRun: aRun(),
        recentRuns: [aRun()],
      }),
      isError: false,
    };
    renderWithProviders(<OverviewPage />);

    expect(screen.queryByText('Latest run')).not.toBeInTheDocument();
    expect(screen.getByText('44m')).toBeInTheDocument();
  });

  it('states the top facts once, without repeating classification or source as a caption', () => {
    queryResult = {
      data: anOverview({
        production: {
          peakRate: '35 requests/sec',
          averageRate: '20 requests/sec',
          p95ObservedRate: null,
          source: 'Dynatrace',
          attributed: true,
          fetched: true,
          observedWindow: '1–7 Aug',
          note: null,
          qualityFacts: [],
          observedMix: [],
          mixCoverage: null,
        },
        capacity: aCapacity(),
      }),
      isError: false,
    };
    renderWithProviders(<OverviewPage />);

    // "Isolated" belongs to the service header; "Manual"/"Dynatrace" are elaboration behind the ⓘ.
    // Neither is a permanent caption under the fact grid's numbers any more.
    expect(screen.queryByText('Isolated')).not.toBeInTheDocument();
    expect(screen.queryByText('Manual')).not.toBeInTheDocument();
    expect(screen.queryByText('Dynatrace')).not.toBeInTheDocument();
  });

  it('offers to set objectives when none are configured, once — never as a tile of its own', () => {
    queryResult = {
      data: anOverview({ objectives: [] }),
      isError: false,
    };
    renderWithProviders(<OverviewPage />);

    expect(screen.getByText(/No objectives configured/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Set them' })).toBeInTheDocument();
  });

  it('drops the objectives hint once at least one objective is configured', () => {
    queryResult = {
      data: anOverview({ objectives: ['p95 < 500 ms'] }),
      isError: false,
    };
    renderWithProviders(<OverviewPage />);

    expect(screen.queryByText(/No objectives configured/)).not.toBeInTheDocument();
  });

  it('no longer carries "Last established" or "Evidence limit" in the rail — Evidence already owns both', () => {
    queryResult = {
      data: anOverview({ capacity: aCapacity() }),
      isError: false,
    };
    renderWithProviders(<OverviewPage />);

    expect(screen.queryByText('Last established')).not.toBeInTheDocument();
    expect(screen.queryByText('Evidence limit')).not.toBeInTheDocument();
  });

  it('names the test once, as a group heading, when every recent run is the same test', () => {
    queryResult = {
      data: anOverview({
        tests: [aTest()],
        recentRuns: [aRun(), aRun({ id: 'run-2', relativeTime: '2 hours ago' })],
      }),
      isError: false,
    };
    renderWithProviders(<OverviewPage />);

    const recentRuns = screen.getByText('Recent runs').closest('section')!;
    // Named once, above the list — not repeated on every row underneath it.
    expect(within(recentRuns).getAllByText('capacity-check')).toHaveLength(1);
  });

  it('names the test on every row once more than one test appears in recent runs', () => {
    queryResult = {
      data: anOverview({
        tests: [aTest(), aTest({ name: 'burst-check' })],
        recentRuns: [aRun(), aRun({ id: 'run-2', testName: 'burst-check' })],
      }),
      isError: false,
    };
    renderWithProviders(<OverviewPage />);

    const recentRuns = screen.getByText('Recent runs').closest('section')!;
    expect(within(recentRuns).getByText('capacity-check')).toBeInTheDocument();
    expect(within(recentRuns).getByText('burst-check')).toBeInTheDocument();
  });

  it('lists tests most-recently-run first, and one that has never run last', () => {
    queryResult = {
      data: anOverview({
        tests: [
          aTest({ name: 'stale-check', latestRun: aRun({ isoTimestamp: '2026-08-20T00:00:00Z' }) }),
          aTest({ name: 'never-run-check', latestRun: null }),
          aTest({ name: 'fresh-check', latestRun: aRun({ isoTimestamp: '2026-08-22T00:00:00Z' }) }),
        ],
      }),
      isError: false,
    };
    const { container } = renderWithProviders(<OverviewPage />);

    const order = Array.from(container.querySelectorAll('[data-test-row]')).map((el) =>
      el.getAttribute('data-test-row'),
    );
    expect(order).toEqual(['fresh-check', 'stale-check', 'never-run-check']);
  });

  it('shows a running test\'s live progress inline in its own row, without navigating away', () => {
    queryResult = {
      data: anOverview({
        header: {
          ...anOverview().header,
          running: {
            id: 'exec-1',
            testName: 'capacity-check',
            testTypeLabel: 'Average load',
            stateLabel: 'Running',
          },
        },
        tests: [aTest()],
      }),
      isError: false,
    };
    renderWithProviders(<OverviewPage />);

    // The row's own state label, since no SSE bucket has arrived in this render.
    expect(screen.getByText('Running')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Run' })).not.toBeInTheDocument();
  });

  it('keeps release-gap and non-quotable-capacity warnings visible with zero interaction', () => {
    queryResult = {
      data: anOverview({
        evidencePredatesRelease: true,
        releaseGapText: 'Tested at 2.16.0; the current release is 2.17.0.',
        capacity: aCapacity({ quotable: false, boundary: 'No compliant level was established.' }),
      }),
      isError: false,
    };
    renderWithProviders(<OverviewPage />);

    expect(
      screen.getByText('This service has not been tested at its current release'),
    ).toBeInTheDocument();
    expect(
      screen.getByText('Tested at 2.16.0; the current release is 2.17.0.'),
    ).toBeInTheDocument();
    expect(screen.getByText('The evidence establishes no capacity boundary')).toBeInTheDocument();
  });

  it('shows the domain\'s short boundary status as a breakpoint cell\'s value, never the full refusal sentence', () => {
    const longSentence =
      'A stable tested capacity boundary was not established by this run: compliance did not '
      + 'move consistently with load.';
    queryResult = {
      data: anOverview({
        capacity: aCapacity({ quotable: false, boundary: longSentence }),
        evidenceByTestType: [
          anEvidence({
            hasEvidence: true,
            outcome: 'FAIL',
            outcomeLabel: 'Fail',
            primaryValueKind: 'OUTCOME',
            primaryValue: 'not established: results were not monotonic',
          }),
        ],
      }),
      isError: false,
    };
    renderWithProviders(<OverviewPage />);

    expect(
      screen.getByText('not established: results were not monotonic'),
    ).toBeInTheDocument();
    // The sentence still appears once, in the Attention banner above — never repeated as a cell's
    // own headline value, where a paragraph would break the rail's short-value convention.
    expect(screen.getAllByText(longSentence)).toHaveLength(1);
  });

  it('selects the test behind the most recently evaluated run when the page first loads', () => {
    queryResult = {
      data: anOverview({
        tests: [
          aTest({
            name: 'capacity-check',
            latestRun: aRun({
              id: 'run-1',
              verdict: 'PASS',
              isoTimestamp: '2026-08-22T04:00:00Z',
            }),
          }),
          // Runs more recently than capacity-check, but was never evaluated — the initial selection
          // still goes to the test with the most recent *evaluated* run, per resolveSelectedTest.
          aTest({
            name: 'smoke-check',
            latestRun: aRun({
              id: 'run-2',
              testName: 'smoke-check',
              verdict: 'NOT_EVALUATED',
              isoTimestamp: '2026-08-22T05:00:00Z',
            }),
          }),
        ],
      }),
      isError: false,
    };
    renderWithProviders(<OverviewPage />);

    expect(
      screen.getByRole('button', { name: "Collapse capacity-check's result" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: "Expand smoke-check's result" }),
    ).toBeInTheDocument();
  });

  it('expands the clicked test\'s own row from its expand control, without navigating away', async () => {
    queryResult = {
      data: anOverview({
        tests: [
          aTest({ name: 'capacity-check', latestRun: aRun({ verdict: 'PASS' }) }),
          aTest({
            name: 'smoke-check',
            latestRun: aRun({ id: 'run-2', testName: 'smoke-check', verdict: 'NOT_EVALUATED' }),
          }),
        ],
      }),
      isError: false,
    };
    renderWithProviders(<OverviewPage />);

    expect(
      screen.getByRole('button', { name: "Collapse capacity-check's result" }),
    ).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: "Expand smoke-check's result" }));

    expect(
      await screen.findByRole('button', { name: "Collapse smoke-check's result" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: "Expand capacity-check's result" }),
    ).toBeInTheDocument();
  });

  it('says how many tests cannot run right now, the way the dedicated Tests page used to', () => {
    queryResult = {
      data: anOverview({
        tests: [aTest(), aTest({ name: 'blocked-check', runnable: false, problems: ['Not reviewed.'] })],
      }),
      isError: false,
    };
    renderWithProviders(<OverviewPage />);

    expect(screen.getByText('1 of 2 cannot run right now. Each says why below.')).toBeInTheDocument();
  });

  it('collapses the expanded test when its own expand control is clicked again', async () => {
    queryResult = {
      data: anOverview({
        tests: [aTest({ name: 'capacity-check', latestRun: aRun({ verdict: 'PASS' }) })],
      }),
      isError: false,
    };
    renderWithProviders(<OverviewPage />);

    // The initial-selection rule opens it by default — no click needed yet.
    expect(
      screen.getByRole('button', { name: "Collapse capacity-check's result" }),
    ).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: "Collapse capacity-check's result" }));

    expect(
      await screen.findByRole('button', { name: "Expand capacity-check's result" }),
    ).toBeInTheDocument();
  });

  it('opens the composer in place from "+ Create test", never a navigation', async () => {
    queryResult = { data: anOverview({ tests: [aTest()] }), isError: false };
    renderWithProviders(<OverviewPage />);

    const createButton = screen.getByRole('button', { name: 'Create test' });
    expect(createButton).not.toHaveAttribute('href');

    await userEvent.click(createButton);

    // What TestComposer itself renders (fields, save/cancel flow) is that component's own test
    // file's job — this only asserts the entry point swapped in place, without a navigation.
    expect(screen.getByRole('heading', { name: 'Compose' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Tests' })).not.toBeInTheDocument();
  });

  it('opens straight into composing when the URL already asks for it', () => {
    queryResult = { data: anOverview({ tests: [aTest()] }), isError: false };
    renderWithProviders(<OverviewPage />, { route: '/?compose=new' });

    expect(screen.getByRole('heading', { name: 'Compose' })).toBeInTheDocument();
  });

  it('opens the composer on a specific test named by the URL, and falls back for a stale one', () => {
    queryResult = { data: anOverview({ tests: [aTest({ name: 'capacity-check' })] }), isError: false };
    const { unmount } = renderWithProviders(<OverviewPage />, {
      route: '/?compose=edit&composeTest=capacity-check',
    });

    expect(screen.getByRole('heading', { name: 'Compose' })).toBeInTheDocument();
    unmount();

    renderWithProviders(<OverviewPage />, { route: '/?compose=edit&composeTest=renamed-away' });
    expect(screen.queryByRole('heading', { name: 'Compose' })).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Tests' })).toBeInTheDocument();
  });

  it('gives the rail to the Workload Preview while composing, and Recent Runs back on Cancel', async () => {
    queryResult = {
      data: anOverview({ tests: [aTest()], recentRuns: [aRun()] }),
      isError: false,
    };
    renderWithProviders(<OverviewPage />);

    expect(screen.getByText('Recent runs')).toBeInTheDocument();
    expect(screen.queryByText('Workload')).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Create test' }));

    expect(screen.getByText('Workload')).toBeInTheDocument();
    expect(screen.queryByText('Recent runs')).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(screen.getByText('Recent runs')).toBeInTheDocument();
    expect(screen.queryByText('Workload')).not.toBeInTheDocument();
  });

  it('cancels back to browsing and clears the URL state', async () => {
    queryResult = { data: anOverview({ tests: [aTest()] }), isError: false };
    renderWithProviders(<OverviewPage />, { route: '/?compose=new' });

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(screen.getByRole('heading', { name: 'Tests' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Compose' })).not.toBeInTheDocument();
  });
});

/**
 * A service Vortex cannot yet measure gets the funnel instead of the workspace — asserted here as
 * "the configured page is gone", because the failure worth catching is the empty fact grid, the
 * empty test list and the empty rail all surviving alongside it.
 */
describe('OverviewPage, before the service can be measured', () => {
  function unsatisfied(): Readiness {
    return {
      canRun: false,
      satisfiedCount: 2,
      totalCount: 7,
      blockerCount: 1,
      nextStepText: 'Add a target so Vortex knows where to send traffic.',
      items: [
        {
          key: 'ENVIRONMENT',
          kind: 'REQUIRED',
          label: 'Environment configured',
          satisfied: false,
          requiredToRun: true,
          effectivelyRequired: true,
          available: true,
          distinct: true,
          blockedBy: [],
          blockedReason: null,
          nextStep: 'Add a target so Vortex knows where to send traffic.',
          href: '/services/checkout/configuration#environments',
        },
        {
          key: 'OBJECTIVES',
          kind: 'ENRICHMENT',
          label: 'Objectives configured',
          satisfied: false,
          requiredToRun: false,
          effectivelyRequired: false,
          available: true,
          distinct: true,
          blockedBy: [],
          blockedReason: null,
          nextStep: 'State what "fast enough" means before a run has to answer it.',
          href: '/services/checkout/configuration#objectives',
        },
      ],
    };
  }

  function unconfigured(overrides: Partial<Overview['header']>): Overview {
    const base = anOverview({
      // Deliberately populated: the gate is about the service, not about whether content exists,
      // and this is what would catch it being written as "hide the empty bits".
      capacity: aCapacity(),
      tests: [aTest()],
      recentRuns: [aRun()],
    });
    return { ...base, header: { ...base.header, readiness: unsatisfied(), ...overrides } };
  }

  it('replaces the readings, the tests and the recent runs when there is no target', () => {
    queryResult = { data: unconfigured({ target: null }), isError: false };
    renderWithProviders(<OverviewPage />);

    expect(screen.getByRole('heading', { name: 'Nothing to measure yet' })).toBeInTheDocument();
    expect(screen.queryByText('Production peak')).not.toBeInTheDocument();
    expect(screen.queryByText('Tested capacity')).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Tests' })).not.toBeInTheDocument();
    expect(screen.queryByText('Recent runs')).not.toBeInTheDocument();
  });

  it('does the same when there is a target but no API description imported', () => {
    queryResult = { data: unconfigured({ operationCount: 0 }), isError: false };
    renderWithProviders(<OverviewPage />);

    expect(screen.getByRole('heading', { name: 'Nothing to measure yet' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Tests' })).not.toBeInTheDocument();
  });

  it('offers every open signal as a control, without leaving the service', () => {
    queryResult = { data: unconfigured({ target: null }), isError: false };
    renderWithProviders(<OverviewPage />);

    expect(screen.getByRole('button', { name: 'Environment configured' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /^Environment configured/ })).not.toBeInTheDocument();
  });

  it('hands the ordinary page back once a target and operations both exist', () => {
    queryResult = { data: anOverview({ capacity: aCapacity(), tests: [aTest()] }), isError: false };
    renderWithProviders(<OverviewPage />);

    expect(screen.getByText('Production peak')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Tests' })).toBeInTheDocument();
    expect(screen.getByText('Recent runs')).toBeInTheDocument();
    expect(
      screen.queryByRole('heading', { name: 'Nothing to measure yet' }),
    ).not.toBeInTheDocument();
  });
});

describe('OverviewPage, asking for the first test', () => {
  it('offers exactly one way to create it', () => {
    queryResult = { data: anOverview({ tests: [] }), isError: false };
    renderWithProviders(<OverviewPage />);

    expect(screen.getAllByRole('button', { name: /^Create a? test$/ })).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Create a test' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Create test' })).not.toBeInTheDocument();
  });

  it('moves that action up to the section header once the list has something in it', () => {
    queryResult = { data: anOverview({ tests: [aTest()] }), isError: false };
    renderWithProviders(<OverviewPage />);

    expect(screen.getByRole('button', { name: 'Create test' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Create a test' })).not.toBeInTheDocument();
  });
});
