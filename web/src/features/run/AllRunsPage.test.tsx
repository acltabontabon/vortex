import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../test/renderWithProviders';
import type { RunHistory } from '../../api/globalRuns';
import { AllRunsPage } from './AllRunsPage';

let queryResult: { data: RunHistory | undefined; isError: boolean } = {
  data: undefined,
  isError: false,
};
const setSearchParams = vi.fn();

vi.mock('../../api/globalRuns', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/globalRuns')>();
  return { ...actual, useRunHistoryQuery: () => queryResult };
});

vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  return {
    ...actual,
    useSearchParams: () => [new URLSearchParams(), setSearchParams],
    useNavigate: () => vi.fn(),
  };
});

function aHistory(overrides: Partial<RunHistory> = {}): RunHistory {
  return {
    rows: [],
    totalBeforeFilters: 0,
    projects: [],
    evaluations: [],
    workloadNames: [],
    environments: [],
    results: [],
    ...overrides,
  };
}

describe('the global runs page', () => {
  it('says no runs exist yet, rather than showing an empty table', () => {
    queryResult = { data: aHistory(), isError: false };
    renderWithProviders(<AllRunsPage />);

    expect(screen.getByText('No runs yet.')).toBeInTheDocument();
  });

  it('lists a run with its service, workload and result', () => {
    queryResult = {
      data: aHistory({
        totalBeforeFilters: 1,
        projects: [{ id: 'checkout', name: 'checkout-service' }],
        rows: [
          {
            executionId: 'exec-1',
            projectId: 'checkout',
            projectName: 'checkout-service',
            serviceVersion: '2.17.0',
            testTypeLabel: 'Average load',
            workloadName: 'average-load',
            environmentName: 'local',
            classificationLabel: 'Isolated',
            terminal: true,
            verdict: 'PASS',
            verdictLabel: 'Met',
            stateLabel: 'Completed',
            offeredLoadDisplay: '50 requests/sec',
            achievedRateDisplay: '49.98',
            p95Display: '49 ms',
            relativeTime: '2m ago',
          },
        ],
      }),
      isError: false,
    };
    renderWithProviders(<AllRunsPage />);

    expect(screen.getByRole('link', { name: /checkout-service/ })).toBeInTheDocument();
    expect(screen.getByText('average-load')).toBeInTheDocument();
    expect(screen.getByText('Met')).toBeInTheDocument();
  });

  it('offers to compare once exactly two rows are selected', async () => {
    queryResult = {
      data: aHistory({
        totalBeforeFilters: 2,
        rows: [
          {
            executionId: 'exec-1',
            projectId: 'checkout',
            projectName: 'checkout-service',
            serviceVersion: null,
            testTypeLabel: 'Average load',
            workloadName: 'average-load',
            environmentName: 'local',
            classificationLabel: 'Isolated',
            terminal: true,
            verdict: 'PASS',
            verdictLabel: 'Met',
            stateLabel: 'Completed',
            offeredLoadDisplay: '50 requests/sec',
            achievedRateDisplay: '49.98',
            p95Display: '49 ms',
            relativeTime: '2m ago',
          },
          {
            executionId: 'exec-2',
            projectId: 'checkout',
            projectName: 'checkout-service',
            serviceVersion: null,
            testTypeLabel: 'Average load',
            workloadName: 'average-load',
            environmentName: 'local',
            classificationLabel: 'Isolated',
            terminal: true,
            verdict: 'PASS',
            verdictLabel: 'Met',
            stateLabel: 'Completed',
            offeredLoadDisplay: '50 requests/sec',
            achievedRateDisplay: '50.01',
            p95Display: '51 ms',
            relativeTime: '1h ago',
          },
        ],
      }),
      isError: false,
    };
    renderWithProviders(<AllRunsPage />);

    const user = userEvent.setup();
    const checkboxes = screen.getAllByRole('checkbox');
    await user.click(checkboxes[0]);
    await user.click(checkboxes[1]);

    expect(screen.getByRole('button', { name: 'Compare' })).toBeInTheDocument();
  });
});
