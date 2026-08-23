import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../test/renderWithProviders';
import type { Run } from '../../api/run';
import { RunPage } from './RunPage';
import { phaseFourEvidence } from '../../test/phaseFourEvidence';

let queryResult: { data: Run | undefined; isError: boolean; refetch: () => void } = {
  data: undefined,
  isError: false,
  refetch: vi.fn(),
};

vi.mock('../../api/run', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/run')>();
  return {
    ...actual,
    useRunQuery: () => queryResult,
    useCancelRunMutation: () => ({ mutate: vi.fn(), isPending: false }),
    useRunAnalysisPanel: () => ({
      status: { data: undefined },
      start: { mutate: vi.fn(), isPending: false },
    }),
  };
});

vi.mock('../../api/runs', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/runs')>();
  return {
    ...actual,
    // Mirrors the real hook's first-render behaviour: it seeds its state from
    // `initialProgress` and returns that immediately, before any SSE event has arrived.
    useRunProgress: (_id: string, options: { initialProgress?: unknown }) =>
      options.initialProgress ?? null,
  };
});

vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  return { ...actual, useParams: () => ({ id: 'exec-1' }) };
});

let capturedConfirm: { onConfirm: () => void } | null = null;

// The confirm dialog itself is LiveExecutionPanel's own concern (and its own test file); here it
// only needs to be capturable so cancellation-requires-confirmation can be observed end to end.
vi.mock('@mantine/modals', () => ({
  modals: {
    openConfirmModal: (opts: { onConfirm: () => void }) => {
      capturedConfirm = opts;
    },
  },
}));

function aPlan(overrides: Partial<Run['plan']> = {}): Run['plan'] {
  return {
    projectId: 'checkout',
    projectName: 'checkout-service',
    testTypeLabel: 'Average load',
    testTypeQuestion: 'Can the service sustain the traffic it typically receives?',
    workloadName: 'average-load',
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
    totalDurationDisplay: '10m',
    ...overrides,
  };
}

function aRun(overrides: Partial<Run> = {}): Run {
  return {
    executionId: 'exec-1',
    running: false,
    terminal: true,
    stateLabel: 'Completed',
    plan: aPlan(),
    progress: null,
    requestedAtDisplay: '22 Aug 2026, 09:00',
    startedAtDisplay: '22 Aug 2026, 09:00',
    failed: false,
    failureLabel: null,
    failureGuidance: null,
    failureDetail: null,
    cancelled: false,
    evidence: null,
    ...overrides,
  };
}

describe('the run page', () => {
  it('shows the live stage while a run is in flight, with no evidence yet', () => {
    queryResult = {
      data: aRun({
        terminal: false,
        running: true,
        stateLabel: 'Running',
        progress: {
          state: 'RUNNING',
          elapsed: '02:15',
          stage: 'Holding at 50 requests/sec',
          percent: 40,
          targetRate: '50/sec',
          currentRate: '49/sec',
          p95: '210 ms',
          errorRate: '0%',
          message: '',
        },
      }),
      isError: false,
      refetch: vi.fn(),
    };
    renderWithProviders(<RunPage />);

    expect(screen.getByText('Holding at 50 requests/sec')).toBeInTheDocument();
    expect(screen.getByText('02:15')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel run' })).toBeInTheDocument();
  });

  it('asks for confirmation before cancelling an in-flight run', async () => {
    capturedConfirm = null;
    queryResult = {
      data: aRun({
        terminal: false,
        running: true,
        stateLabel: 'Running',
        progress: {
          state: 'RUNNING',
          elapsed: '02:15',
          stage: 'Holding at 50 requests/sec',
          percent: 40,
          targetRate: '50/sec',
          currentRate: '49/sec',
          p95: '210 ms',
          errorRate: '0%',
          message: '',
        },
      }),
      isError: false,
      refetch: vi.fn(),
    };
    renderWithProviders(<RunPage />);

    await userEvent.click(screen.getByRole('button', { name: 'Cancel run' }));

    // The mutation itself (via useCancelRunMutation) is exercised by LiveExecutionPanel's own
    // tests — this only confirms the page wires a real confirmation step in, not a bare click.
    expect(capturedConfirm).not.toBeNull();
  });

  it('states the failure guidance plainly when a run failed', () => {
    queryResult = {
      data: aRun({
        failed: true,
        failureLabel: 'Could not reach the target',
        failureGuidance: 'Check that the environment URL is correct and reachable.',
      }),
      isError: false,
      refetch: vi.fn(),
    };
    renderWithProviders(<RunPage />);

    expect(screen.getByText('Could not reach the target')).toBeInTheDocument();
    expect(
      screen.getByText('Check that the environment URL is correct and reachable.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Run again' })).toBeInTheDocument();
  });

  it('answers the question and offers the shareable report once evidence exists', () => {
    queryResult = {
      data: aRun({
        evidence: {
          ...phaseFourEvidence(),
          identity: {
            executionId: 'exec-1',
            shortId: 'exec-1',
            serviceName: 'checkout-service',
            serviceVersion: '2.17.0',
            workloadName: 'average-load',
            testTypeLabel: 'Average load',
            environmentName: 'local',
            environmentTypeLabel: 'Local',
            classification: 'ISOLATED',
            classificationLabel: 'Isolated',
            targetUrl: 'https://checkout.internal',
            targetWasRewritten: false,
            targetRewriteReason: null,
            targetKind: 'EXTERNAL_ENDPOINT',
            targetSummary: 'https://checkout.internal',
            targetOwnershipLabel: 'Externally managed',
            resourceSummary: null,
            requestedAtIso: '2026-08-22T09:00:00Z',
            finishedAtDisplay: '22 Aug 2026, 09:10',
            durationDisplay: '10m',
            testType: 'AVERAGE_LOAD',
          },
          verdict: {
            question: 'Can the service sustain the traffic it typically receives?',
            verdict: 'PASS',
            verdictLabel: 'Met',
            answer: 'Yes. The service met every objective.',
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
            requestsDisplay: '30,000',
            estimatedRequestsDisplay: null,
            errorRateDisplay: '0%',
            failuresDisplay: '0',
            configuredDurationDisplay: '10m',
            actualDurationDisplay: '10m',
            operationMix: ['getOrder 100%'],
            scriptSourceLabel: 'Generated',
          },
          performance: {
            latencyRows: [{ percentileLabel: 'p95', durationDisplay: '210 ms' }],
            maxLatencyDisplay: '320 ms',
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
          acceptance: { hasObjectives: true, results: [], absenceExplanation: null },
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
            reproductionCommand: 'workload average-load',
            hasArtifacts: false,
            artifactNames: [],
          },
          releaseMoved: false,
          previousCompatibleExecutionId: null,
        },
      }),
      isError: false,
      refetch: vi.fn(),
    };
    renderWithProviders(<RunPage />);

    expect(screen.getByText('Yes. The service met every objective.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Share report' })).toHaveAttribute(
      'href',
      '/runs/exec-1/report',
    );
  });
});
