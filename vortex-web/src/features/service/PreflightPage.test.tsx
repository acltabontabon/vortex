import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../test/renderWithProviders';
import type { Preflight } from '../../api/run';
import { PreflightPage } from './PreflightPage';

let queryResult: { data: Preflight | undefined; isError: boolean } = {
  data: undefined,
  isError: false,
};
const startMutate = vi.fn();
const navigate = vi.fn();

vi.mock('../../api/run', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/run')>();
  return {
    ...actual,
    usePreflightQuery: () => queryResult,
    useStartRunMutation: () => ({ mutate: startMutate, isPending: false, isError: false, data: undefined }),
  };
});

vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  return {
    ...actual,
    useParams: () => ({ id: 'checkout' }),
    useSearchParams: () => [new URLSearchParams('workload=average-load&environment=local')],
    useNavigate: () => navigate,
  };
});

function aPreflight(overrides: Partial<Preflight> = {}): Preflight {
  return {
    canRun: true,
    plainEnglishSummary: 'Vortex will send 50 requests/sec against local for 10 minutes.',
    classification: 'ISOLATED',
    classificationLabel: 'Isolated',
    classificationCaveat: null,
    targetRewritten: false,
    configuredTarget: 'https://checkout.internal',
    effectiveTarget: 'https://checkout.internal',
    targetRewriteReason: null,
    testTypeLabel: 'Average load',
    testTypeQuestion: 'Can the service sustain the traffic it typically receives?',
    workloadName: 'average-load',
    environmentName: 'local',
    environmentTypeLabel: 'Local',
    dependencyModeLabel: 'Mocked',
    durationDisplay: '10m',
    workloadModelLabel: 'Arrival rate',
    peakLevelDisplay: '50 requests/sec',
    workloadSourceDescribe: 'Entered by hand',
    operations: [{ name: 'getOrder', sharePercent: '100%', rateDisplay: '50/sec' }],
    compositionRenderable: false,
    compositionSvg: null,
    offeredLoad: '50 requests/sec',
    hasRequestEstimate: true,
    requests: 30000,
    estimateCaveat: null,
    mutatingOperations: [],
    checks: [{ name: 'Reachability', statusKind: 'PASS', statusLabel: 'Pass', detail: 'Reached the target.', remedy: null }],
    safetyFindings: [],
    requiredChallenges: [],
    fingerprintShortHash: 'a1b2c3',
    runnerLabel: 'k6',
    scriptSourceLabel: 'Generated',
    thresholdDescriptions: ['p95 < 500 ms'],
    error: null,
    errorDetails: [],
    ...overrides,
  };
}

describe('the preflight page', () => {
  it('states what will happen in the domain\'s own summary', () => {
    queryResult = { data: aPreflight(), isError: false };
    renderWithProviders(<PreflightPage />);

    expect(
      screen.getByText('Vortex will send 50 requests/sec against local for 10 minutes.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Run' })).toBeEnabled();
  });

  it('says why, rather than failing opaquely, when nothing can run yet', () => {
    queryResult = {
      data: aPreflight({
        canRun: false,
        testTypeLabel: null,
        error: 'This project needs at least one workload and one environment before it can run a test.',
        errorDetails: [],
      }),
      isError: false,
    };
    renderWithProviders(<PreflightPage />);

    expect(
      screen.getByText('This project needs at least one workload and one environment before it can run a test.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Run' })).not.toBeInTheDocument();
  });

  it('disables the run button when a check failed', () => {
    queryResult = {
      data: aPreflight({
        canRun: false,
        checks: [
          { name: 'Reachability', statusKind: 'FAIL', statusLabel: 'Failed', detail: 'Could not reach the target.', remedy: 'Check the environment URL.' },
        ],
      }),
      isError: false,
    };
    renderWithProviders(<PreflightPage />);

    expect(screen.getByRole('button', { name: 'Run' })).toBeDisabled();
    expect(screen.getAllByText(/Could not reach the target/).length).toBeGreaterThan(0);
  });

  it('requires the exact typed confirmation before a mutating run can start', () => {
    queryResult = {
      data: aPreflight({ requiredChallenges: ['send test'] }),
      isError: false,
    };
    renderWithProviders(<PreflightPage />);

    expect(screen.getByRole('button', { name: 'Run' })).toBeDisabled();
  });

  it('returns to the service workspace after starting a run, not a dedicated progress page', async () => {
    queryResult = { data: aPreflight(), isError: false };
    startMutate.mockImplementation((_request, options) => {
      options.onSuccess({ started: true, executionId: 'exec-1' });
    });
    renderWithProviders(<PreflightPage />);

    await userEvent.click(screen.getByRole('button', { name: 'Run' }));

    // The run now shows live, inline, on the test's own row in the service workspace — there's
    // nothing a dedicated progress page would add.
    expect(navigate).toHaveBeenCalledWith('/services/checkout');
    expect(navigate).not.toHaveBeenCalledWith('/runs/exec-1');
  });
});
