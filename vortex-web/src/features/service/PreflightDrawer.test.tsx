import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../test/renderWithProviders';
import type { Preflight } from '../../api/run';
import { PreflightDrawer } from './PreflightDrawer';

let queryResult: { data: Preflight | undefined; isError: boolean } = {
  data: undefined,
  isError: false,
};
const startMutate = vi.fn();

vi.mock('../../api/run', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/run')>();
  return {
    ...actual,
    usePreflightQuery: () => queryResult,
    useStartRunMutation: () => ({ mutate: startMutate, isPending: false, isError: false, data: undefined }),
  };
});

function aPreflight(overrides: Partial<Preflight> = {}): Preflight {
  return {
    canRun: true,
    plainEnglishSummary: 'Vortex will send 50 requests/sec against local for 1 minute.',
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

describe('the preflight drawer', () => {
  it('shows the same facts a full-page preflight would, without navigating anywhere', () => {
    queryResult = { data: aPreflight(), isError: false };
    renderWithProviders(
      <PreflightDrawer
        serviceId="checkout"
        workload="capacity-check"
        environment="local"
        opened
        onClose={vi.fn()}
      />,
    );

    expect(
      screen.getByText('Vortex will send 50 requests/sec against local for 1 minute.'),
    ).toBeInTheDocument();
    expect(screen.getByText('Ready to run')).toBeInTheDocument();
    // The test type label is stated once, as the drawer's own title, not repeated as a second
    // heading inside the body.
    expect(screen.getAllByText('Average load')).toHaveLength(1);
  });

  it('closes without starting anything when Cancel is clicked', async () => {
    queryResult = { data: aPreflight(), isError: false };
    const onClose = vi.fn();
    renderWithProviders(
      <PreflightDrawer serviceId="checkout" workload="capacity-check" environment="local" opened onClose={onClose} />,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(onClose).toHaveBeenCalled();
    expect(startMutate).not.toHaveBeenCalled();
  });

  it('closes itself once the run starts, instead of navigating to a progress page', async () => {
    queryResult = { data: aPreflight(), isError: false };
    startMutate.mockImplementation((_request, options) => {
      options.onSuccess({ started: true, executionId: 'exec-1' });
    });
    const onClose = vi.fn();
    renderWithProviders(
      <PreflightDrawer serviceId="checkout" workload="capacity-check" environment="local" opened onClose={onClose} />,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Run' }));

    expect(onClose).toHaveBeenCalled();
  });
});
