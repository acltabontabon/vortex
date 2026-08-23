import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../test/renderWithProviders';
import type { Capacity, RunRef, TestRow as Test } from '../../api/workspace';
import type { Preflight } from '../../api/run';
import { TestRow } from './TestRow';

function aCapacity(overrides: Partial<Capacity> = {}): Capacity {
  return {
    compliantLevel: '100 requests/sec',
    label: 'Tested SLO-compliant capacity',
    boundary: '100 requests/sec compliant → 250 requests/sec non-compliant',
    boundaryLabel: 'Tested capacity boundary',
    quotable: true,
    boundaryStatus: 'ESTABLISHED',
    boundaryStatusLabel: 'boundary established',
    boundaryStrength: 'High',
    firstNonCompliant: '250 requests/sec',
    headroom: null,
    headroomRefusal: 'This capacity was measured in an isolated test.',
    serviceVersion: '2.17.0',
    environmentName: 'local',
    classification: 'ISOLATED',
    dependencyMode: 'MOCKED',
    workloadName: 'breakpoint-check',
    operationMix: ['getOrder 100%'],
    objectives: ['p95 < 500 ms'],
    durationDisplay: '5m',
    measuredAt: '22 Aug 2026, 04:55',
    runId: 'exec-1',
    conditions: ['Isolated test, dependencies mocked'],
    constraintCandidates: [],
    ...overrides,
  };
}

const duplicateMutate = vi.fn();
const deleteMutate = vi.fn();

let preflightResult: { data: Preflight | undefined; isError: boolean } = {
  data: undefined,
  isError: false,
};
const startMutate = vi.fn();

vi.mock('../../api/run', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/run')>();
  return {
    ...actual,
    // TestResult (rendered inline once a row expands) fetches its own run's full evidence for the
    // latency/objective breakdown — this file is about TestRow's own conditional logic, not that
    // enrichment, so it stays unresolved and TestResult falls back to the facts Overview already
    // sent.
    useRunQuery: () => ({ data: undefined }),
    // The run drawer's own logic (usePreflightFlow) has its own test coverage — here it only needs
    // to open with something in it when Run is clicked.
    usePreflightQuery: () => preflightResult,
    useStartRunMutation: () => ({ mutate: startMutate, isPending: false, isError: false, data: undefined }),
  };
});

function aPreflight(overrides: Partial<Preflight> = {}): Preflight {
  return {
    canRun: true,
    plainEnglishSummary: null,
    classification: 'ISOLATED',
    classificationLabel: 'Isolated',
    classificationCaveat: null,
    targetRewritten: false,
    configuredTarget: 'http://localhost:8080',
    effectiveTarget: 'http://localhost:8080',
    targetRewriteReason: null,
    testTypeLabel: 'Average load',
    testTypeQuestion: 'Does the service meet its objectives under the traffic it normally receives?',
    workloadName: 'capacity-check',
    environmentName: 'local',
    environmentTypeLabel: 'Local',
    dependencyModeLabel: 'Mocked',
    durationDisplay: '1m',
    workloadModelLabel: 'Arrival rate',
    peakLevelDisplay: '50 requests/sec',
    workloadSourceDescribe: 'Entered by hand',
    operations: [],
    compositionRenderable: false,
    compositionSvg: null,
    offeredLoad: '50 requests/sec',
    hasRequestEstimate: false,
    requests: null,
    estimateCaveat: null,
    mutatingOperations: [],
    checks: [],
    safetyFindings: [],
    requiredChallenges: [],
    fingerprintShortHash: 'a1b2c3',
    runnerLabel: 'k6',
    scriptSourceLabel: 'Generated',
    thresholdDescriptions: [],
    error: null,
    errorDetails: [],
    ...overrides,
  };
}

vi.mock('../../api/tests', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/tests')>();
  return {
    ...actual,
    useDuplicateTestMutation: () => ({ mutate: duplicateMutate, isPending: false }),
    useDeleteTestMutation: () => ({ mutate: deleteMutate, isPending: false }),
  };
});

// This file is about TestRow's own conditional logic — what it swaps in and out of the row for
// each running state — not RunningTestPanel's internals, which have their own test file.
vi.mock('./RunningTestPanel', () => ({
  RunningTestPanel: ({ running }: { running: RunRef }) => (
    <div data-testid="running-test-panel">{running.testName}</div>
  ),
}));

// The confirm dialog itself needs a ModalsProvider this test doesn't wrap with; what matters here
// is that Delete asks for confirmation and only calls the mutation once confirmed.
vi.mock('@mantine/modals', () => ({
  modals: { openConfirmModal: (opts: { onConfirm: () => void }) => opts.onConfirm() },
}));

/**
 * What a test row has to communicate, in five seconds, without the reader knowing anything about
 * the service.
 *
 * <p>These assert product decisions rather than markup: that a runnable test offers to run, that a
 * blocked one says why instead of going quiet, that provenance never drops off (even if the full
 * sentence now lives one click away, in the details drawer), and that a level is never shown as a
 * bare number.
 */
function aTest(overrides: Partial<Test> = {}): Test {
  return {
    name: 'capacity-check',
    description: null,
    question: 'Does the service meet its objectives under the traffic it normally receives?',
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

async function openMenu() {
  await userEvent.click(screen.getByRole('button', { name: 'More actions' }));
}

/** Opens the definition drawer the way a user now does — from the row's overflow menu. */
async function openDrawer() {
  await openMenu();
  await userEvent.click(await screen.findByRole('menuitem', { name: 'View definition' }));
}

const noopSelect = () => {};
const noopEdit = () => {};

describe('a test row', () => {
  it('opens preflight in a drawer, in place, rather than navigating to a separate page', async () => {
    preflightResult = { data: aPreflight(), isError: false };
    renderWithProviders(<TestRow serviceId="checkout" test={aTest()} onSelect={noopSelect} onEdit={noopEdit} />);

    const run = screen.getByRole('button', { name: 'Run' });
    expect(run).not.toHaveAttribute('href');
    expect(screen.queryByText('Ready to run')).not.toBeInTheDocument();

    await userEvent.click(run);

    expect(await screen.findByText('Ready to run')).toBeInTheDocument();
  });

  it('states the question the test answers', () => {
    renderWithProviders(<TestRow serviceId="checkout" test={aTest()} onSelect={noopSelect} onEdit={noopEdit} />);

    expect(
      screen.getByText(
        'Does the service meet its objectives under the traffic it normally receives?',
      ),
    ).toBeInTheDocument();
  });

  it('shows the offered load with its unit, never as a bare number', () => {
    renderWithProviders(<TestRow serviceId="checkout" test={aTest()} onSelect={noopSelect} onEdit={noopEdit} />);

    expect(screen.getByText(/50 req\/s/)).toBeInTheDocument();
  });

  it('keeps provenance off the row — it lives in the details drawer now', () => {
    renderWithProviders(<TestRow serviceId="checkout" test={aTest()} onSelect={noopSelect} onEdit={noopEdit} />);

    expect(screen.queryByText(/Manually entered/)).not.toBeInTheDocument();
  });

  it('omits the environment when it matches the service default, so the header is not repeated', () => {
    renderWithProviders(
      <TestRow
        serviceId="checkout"
        test={aTest()}
        defaultEnvironment="local"
        onSelect={noopSelect} onEdit={noopEdit}
      />,
    );

    expect(screen.queryByText('local')).not.toBeInTheDocument();
  });

  it('states the environment when it differs from the service default', () => {
    renderWithProviders(
      <TestRow
        serviceId="checkout"
        test={aTest()}
        defaultEnvironment="staging"
        onSelect={noopSelect} onEdit={noopEdit}
      />,
    );

    expect(screen.getByText('local')).toBeInTheDocument();
  });

  it('does not clutter the row with operation count', () => {
    renderWithProviders(<TestRow serviceId="checkout" test={aTest()} onSelect={noopSelect} onEdit={noopEdit} />);

    expect(screen.queryByText(/operation/)).not.toBeInTheDocument();
  });

  it('names the production comparison only inside the details drawer, not permanently on the row', async () => {
    const test = aTest({
      source: {
        kind: 'DERIVED_FROM_OBSERVATION',
        label: 'Derived from observed production traffic',
        describe: 'Derived from observed production traffic',
        detail: 'Dynatrace',
        productionInformed: true,
        observedWindow: '1–7 Aug',
        derivation: 'observed peak 35 × 1.5 = 53',
      },
      versusProduction: '1.43× observed production peak',
    });
    renderWithProviders(<TestRow serviceId="checkout" test={test} onSelect={noopSelect} onEdit={noopEdit} />);

    expect(screen.queryByText(/Derived from observed production traffic/)).not.toBeInTheDocument();

    await openDrawer();

    expect(
      await screen.findByText(
        'Derived from observed production traffic — Dynatrace · 1.43× observed production peak',
      ),
    ).toBeInTheDocument();
  });

  it('opens the drawer with the traffic distribution, not an inline collapse, from the overflow menu', async () => {
    const test = aTest({
      composition: [
        {
          operationId: 'op-1',
          label: 'List orders',
          method: 'GET',
          path: '/orders',
          sharePercent: '100%',
          shareFraction: 1,
          rateDisplay: '50',
          known: true,
        },
      ],
    });
    renderWithProviders(<TestRow serviceId="checkout" test={test} onSelect={noopSelect} onEdit={noopEdit} />);

    expect(screen.queryByText('/orders')).not.toBeInTheDocument();

    await openDrawer();

    expect(await screen.findByText('/orders')).toBeInTheDocument();
  });

  it('expands or collapses only from its own dedicated control, never from clicking elsewhere on the row', async () => {
    const onSelect = vi.fn();
    renderWithProviders(<TestRow serviceId="checkout" test={aTest()} onSelect={onSelect} onEdit={noopEdit} />);

    // Run, the kebab menu and a link to the latest run all live inside this same row — a whole-row
    // click target would either swallow those or need special-casing around every one of them.
    await userEvent.click(screen.getByText('capacity-check'));
    await userEvent.click(
      screen.getByText('Does the service meet its objectives under the traffic it normally receives?'),
    );
    expect(onSelect).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole('button', { name: "Expand capacity-check's result" }));
    expect(onSelect).toHaveBeenCalledWith('capacity-check');

    // The drawer never opens from expanding either.
    expect(screen.queryByText('Workload')).not.toBeInTheDocument();
  });

  it('labels its expand control by name and current state, for a keyboard or screen-reader user', () => {
    renderWithProviders(
      <TestRow serviceId="checkout" test={aTest()} onSelect={noopSelect} onEdit={noopEdit} selected />,
    );

    // Selected: the same control now reads "Collapse", and says so to assistive tech via aria-expanded.
    expect(
      screen.getByRole('button', { name: "Collapse capacity-check's result" }),
    ).toHaveAttribute('aria-expanded', 'true');
  });

  it('explains a blocked test instead of hiding it, and offers no Run', () => {
    renderWithProviders(
      <TestRow
        serviceId="checkout"
        test={aTest({
          runnable: false,
          problems: ['POST /orders is a mutating operation and has not been reviewed.'],
        })}
        onSelect={noopSelect} onEdit={noopEdit}
      />,
    );

    expect(screen.getByText('capacity-check')).toBeInTheDocument();
    expect(screen.getByText('cannot run')).toBeInTheDocument();
    expect(
      screen.getByText('POST /orders is a mutating operation and has not been reviewed.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Run' })).not.toBeInTheDocument();
    // Not a disabled button either — the only control goes somewhere.
    expect(screen.getByRole('link', { name: 'Fix this' })).toBeInTheDocument();
  });

  it('shows the latest verdict as a word, not only a colour', () => {
    renderWithProviders(
      <TestRow
        serviceId="checkout"
        test={aTest({
          runCount: 8,
          latestRun: {
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
            relativeTime: '12 minutes ago',
            isoTimestamp: '2026-08-22T04:55:00Z',
            matchesCurrentTest: true,
            differences: [],
          },
        })}
        onSelect={noopSelect} onEdit={noopEdit}
      />,
    );

    expect(screen.getByText('Pass')).toBeInTheDocument();
    expect(screen.getByText('12m')).toBeInTheDocument();
    expect(screen.getByText('· 8 runs')).toBeInTheDocument();
  });

  it('reports drift with its arithmetic rather than applying it', () => {
    renderWithProviders(
      <TestRow
        serviceId="checkout"
        test={aTest({
          drift: {
            kind: 'DRIFTED',
            statement: 'Production now averages more than this test assumes.',
            derivedFrom: '50 requests/sec',
            proposedNow: '72 requests/sec',
            derivation: 'observed peak 48 × 1.5 = 72',
          },
        })}
        onSelect={noopSelect} onEdit={noopEdit}
      />,
    );

    expect(
      screen.getByText('Production now averages more than this test assumes.'),
    ).toBeInTheDocument();
    expect(screen.getByText('observed peak 48 × 1.5 = 72')).toBeInTheDocument();
  });

  it('says nothing about drift the domain could not assess', () => {
    renderWithProviders(
      <TestRow
        serviceId="checkout"
        test={aTest({
          drift: {
            kind: 'NOT_ASSESSABLE',
            statement: 'This test was written by hand, so there is no assumption to check.',
            derivedFrom: null,
            proposedNow: null,
            derivation: null,
          },
        })}
        onSelect={noopSelect} onEdit={noopEdit}
      />,
    );

    expect(
      screen.queryByText('This test was written by hand, so there is no assumption to check.'),
    ).not.toBeInTheDocument();
  });

  it('duplicates a test and opens the composer on the copy, in place — never a navigation', async () => {
    duplicateMutate.mockImplementation((_name, options) => {
      options.onSuccess({ name: 'capacity-check-copy' });
    });
    const onEdit = vi.fn();
    renderWithProviders(<TestRow serviceId="checkout" test={aTest()} onSelect={noopSelect} onEdit={onEdit} />);

    await openMenu();
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Duplicate' }));

    expect(duplicateMutate).toHaveBeenCalledWith('capacity-check', expect.anything());
    expect(onEdit).toHaveBeenCalledWith('capacity-check-copy');
  });

  it('opens the composer on this test from the overflow menu, never a navigation', async () => {
    const onEdit = vi.fn();
    renderWithProviders(<TestRow serviceId="checkout" test={aTest()} onSelect={noopSelect} onEdit={onEdit} />);

    await openMenu();
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Edit test' }));

    expect(onEdit).toHaveBeenCalledWith('capacity-check');
  });

  it('asks for confirmation before deleting, from the overflow menu', async () => {
    renderWithProviders(<TestRow serviceId="checkout" test={aTest()} onSelect={noopSelect} onEdit={noopEdit} />);

    await openMenu();
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Delete' }));

    expect(deleteMutate).toHaveBeenCalledWith('capacity-check', expect.anything());
  });

  it('shows the running panel instead of facts and the Run button when this test is the one running', () => {
    const running: RunRef = {
      id: 'exec-1',
      testName: 'capacity-check',
      testTypeLabel: 'Average load',
      stateLabel: 'Running',
    };
    renderWithProviders(
      <TestRow serviceId="checkout" test={aTest()} running={running} onSelect={noopSelect} onEdit={noopEdit} />,
    );

    expect(screen.getByTestId('running-test-panel')).toHaveTextContent('capacity-check');
    expect(screen.queryByRole('button', { name: 'Run' })).not.toBeInTheDocument();
    expect(screen.queryByText(/50 req\/s/)).not.toBeInTheDocument();
    expect(screen.queryByText('Never run')).not.toBeInTheDocument();

    // Editing the definition doesn't touch the execution already in flight.
    expect(screen.getByRole('button', { name: 'More actions' })).toBeInTheDocument();
  });

  it('shows a quiet waiting indicator, not a disabled Run button, when a different test is running', () => {
    const running: RunRef = {
      id: 'exec-1',
      testName: 'other-test',
      testTypeLabel: 'Average load',
      stateLabel: 'Running',
    };
    renderWithProviders(
      <TestRow serviceId="checkout" test={aTest()} running={running} onSelect={noopSelect} onEdit={noopEdit} />,
    );

    expect(screen.queryByRole('button', { name: 'Run' })).not.toBeInTheDocument();
    expect(screen.getByText('Waiting for other-test')).toBeInTheDocument();
    // Everything else about the row still renders normally.
    expect(screen.getByText(/50 req\/s/)).toBeInTheDocument();
    expect(screen.getByText('Never run')).toBeInTheDocument();
  });

  it('runs exactly as before when nothing is running', () => {
    renderWithProviders(
      <TestRow serviceId="checkout" test={aTest()} running={null} onSelect={noopSelect} onEdit={noopEdit} />,
    );

    expect(screen.getByRole('button', { name: 'Run' })).toBeInTheDocument();
    expect(screen.queryByTestId('running-test-panel')).not.toBeInTheDocument();
    expect(screen.queryByText(/Waiting for/)).not.toBeInTheDocument();
  });

  it('reduces the previous result to a subtle, non-interactive line while this test is running', () => {
    const running: RunRef = {
      id: 'exec-2',
      testName: 'capacity-check',
      testTypeLabel: 'Average load',
      stateLabel: 'Running',
    };
    const test = aTest({
      runCount: 8,
      latestRun: {
        id: 'run-1',
        verdict: 'FAIL',
        verdictLabel: 'Fail',
        stateLabel: 'Completed',
        terminal: true,
        testName: 'capacity-check',
        testType: 'AVERAGE_LOAD',
        testTypeLabel: 'Average load',
        levelDisplay: '50 requests/sec',
        environmentName: 'local',
        classification: 'ISOLATED',
        release: '2.17.0',
        answer: 'Objectives were not met at 50 requests/sec.',
        p95: '900 ms',
        durationDisplay: '1m',
        relativeTime: '4 hours ago',
        isoTimestamp: '2026-08-22T00:00:00Z',
        matchesCurrentTest: true,
        differences: [],
      },
    });
    renderWithProviders(
      <TestRow serviceId="checkout" test={test} running={running} onSelect={noopSelect} onEdit={noopEdit} />,
    );

    expect(screen.getByText('Fail')).toBeInTheDocument();
    expect(screen.getByText('Previous · 4h')).toBeInTheDocument();
    // Not the interactive pill shown when idle — no link, no run count.
    expect(screen.queryByRole('link', { name: /Fail/ })).not.toBeInTheDocument();
    expect(screen.queryByText('· 8 runs')).not.toBeInTheDocument();
  });

  it("hides this test's own previous result the moment it starts running, even if the row was already expanded — the reported bug", () => {
    const running: RunRef = {
      id: 'exec-2',
      testName: 'capacity-check',
      testTypeLabel: 'Average load',
      stateLabel: 'Running',
    };
    const test = aTest({
      runCount: 12,
      latestRun: {
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
        relativeTime: '57 minutes ago',
        isoTimestamp: '2026-08-22T04:55:00Z',
        matchesCurrentTest: true,
        differences: [],
      },
    });
    renderWithProviders(
      <TestRow serviceId="checkout" test={test} running={running} selected onSelect={noopSelect} onEdit={noopEdit} />,
    );

    // TestResult's own provenance line and its "View full result" link are how it states whose
    // evidence this is — neither belongs on screen while the live panel owns the row's attention.
    expect(screen.queryByText('Run #12 · 57m')).not.toBeInTheDocument();
    expect(screen.queryByText('View full result →')).not.toBeInTheDocument();
    expect(screen.getByTestId('running-test-panel')).toBeInTheDocument();
  });

  it("shows this test's fresh result in place once its run finishes, without a page reload", async () => {
    const running: RunRef = {
      id: 'exec-2',
      testName: 'capacity-check',
      testTypeLabel: 'Average load',
      stateLabel: 'Running',
    };
    const oldRun = {
      id: 'run-1',
      verdict: 'PASS' as const,
      verdictLabel: 'Pass',
      stateLabel: 'Completed',
      terminal: true,
      testName: 'capacity-check',
      testType: 'AVERAGE_LOAD',
      testTypeLabel: 'Average load',
      levelDisplay: '50 requests/sec',
      environmentName: 'local',
      classification: 'ISOLATED' as const,
      release: '2.17.0',
      answer: 'Objectives held at 50 requests/sec.',
      p95: '120 ms',
      durationDisplay: '1m',
      relativeTime: '57 minutes ago',
      isoTimestamp: '2026-08-22T04:55:00Z',
      matchesCurrentTest: true,
      differences: [],
    };

    const { rerender } = renderWithProviders(
      <TestRow
        serviceId="checkout"
        test={aTest({ runCount: 12, latestRun: oldRun })}
        running={running}
        selected
        onSelect={noopSelect} onEdit={noopEdit}
      />,
    );

    expect(screen.queryByText('Run #12 · 57m')).not.toBeInTheDocument();

    const newRun = { ...oldRun, id: 'run-2', relativeTime: '1 minute ago' };
    rerender(
      <TestRow
        serviceId="checkout"
        test={aTest({ runCount: 13, latestRun: newRun })}
        running={null}
        selected
        onSelect={noopSelect} onEdit={noopEdit}
      />,
    );

    expect(screen.queryByTestId('running-test-panel')).not.toBeInTheDocument();
    // Mantine's Collapse animates open over its own transition — findBy waits that out rather
    // than asserting on the animation itself.
    expect(await screen.findByText('Run #13 · 1m')).toBeInTheDocument();
  });

  it('reveals this test\'s own evidence inline, directly under the row, once expanded', async () => {
    const test = aTest({
      runCount: 12,
      latestRun: {
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
        relativeTime: '57 minutes ago',
        isoTimestamp: '2026-08-22T04:55:00Z',
        matchesCurrentTest: true,
        differences: [],
      },
      capacity: null,
      range: { renderable: false, unit: null, markers: [], openEnded: false },
    });
    renderWithProviders(<TestRow serviceId="checkout" test={test} onSelect={noopSelect} onEdit={noopEdit} selected />);

    // Its own row, not a separate section elsewhere — the provenance line names this exact run.
    expect(screen.getByText('Run #12 · 57m')).toBeInTheDocument();
  });

  it('drops the row\'s own verdict/time/count once expanded, so the same verdict isn\'t stated twice', () => {
    const test = aTest({
      runCount: 12,
      latestRun: {
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
        relativeTime: '57 minutes ago',
        isoTimestamp: '2026-08-22T04:55:00Z',
        matchesCurrentTest: true,
        differences: [],
      },
    });
    renderWithProviders(<TestRow serviceId="checkout" test={test} onSelect={noopSelect} onEdit={noopEdit} selected />);

    // TestResult below states the verdict and the run's age/count in its own provenance line — the
    // row's own copy would only repeat the same word ("Pass") a second time in the same glance.
    expect(screen.getAllByText('Pass')).toHaveLength(1);
    expect(screen.queryByText('· 12 runs')).not.toBeInTheDocument();
  });

  it('never shows evidence for a test that is not the one selected', () => {
    const test = aTest({ latestRun: null });
    renderWithProviders(
      <TestRow serviceId="checkout" test={test} onSelect={noopSelect} onEdit={noopEdit} selected={false} />,
    );

    expect(screen.queryByText(/No result yet/)).not.toBeInTheDocument();
  });

  it('states a saturating test\'s boundary sentence always, never gated behind expanding', () => {
    const test = aTest({
      name: 'breakpoint-check',
      saturating: true,
      capacity: aCapacity({ boundaryStatus: 'ESTABLISHED' }),
    });
    renderWithProviders(
      <TestRow serviceId="checkout" test={test} onSelect={noopSelect} onEdit={noopEdit} selected={false} />,
    );

    // Not selected/expanded, and the sentence is still right there on the row.
    expect(
      screen.getByText(
        'Boundary found: 100 requests/sec compliant → 250 requests/sec non-compliant',
      ),
    ).toBeInTheDocument();
  });

  it('leaves the boundary note calm, with no "found" framing, when no boundary was established', () => {
    const test = aTest({
      name: 'breakpoint-check',
      saturating: true,
      capacity: aCapacity({
        boundaryStatus: 'UNSTABLE',
        boundary: 'A stable tested capacity boundary was not established by this run.',
      }),
    });
    renderWithProviders(<TestRow serviceId="checkout" test={test} onSelect={noopSelect} onEdit={noopEdit} />);

    expect(screen.queryByText(/Boundary found:/)).not.toBeInTheDocument();
    expect(
      screen.getByText('A stable tested capacity boundary was not established by this run.'),
    ).toBeInTheDocument();
  });

  it('never states a boundary sentence for a non-saturating test', () => {
    const test = aTest({ saturating: false, capacity: aCapacity({ boundaryStatus: 'ESTABLISHED' }) });
    renderWithProviders(<TestRow serviceId="checkout" test={test} onSelect={noopSelect} onEdit={noopEdit} />);

    expect(screen.queryByText(/Boundary found:/)).not.toBeInTheDocument();
  });
});
