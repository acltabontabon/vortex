import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../../test/renderWithProviders';
import type { CompareResult } from '../../api/globalRuns';
import { ComparePage } from './ComparePage';

let compareResult: { data: CompareResult | undefined; isError: boolean } = {
  data: undefined,
  isError: false,
};

vi.mock('../../api/globalRuns', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/globalRuns')>();
  return {
    ...actual,
    useCompareQuery: () => compareResult,
    useComparisonAnalysisPanel: () => ({
      status: { data: undefined },
      start: { mutate: vi.fn(), isPending: false },
    }),
  };
});

vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  return {
    ...actual,
    useSearchParams: () => [new URLSearchParams('baseline=exec-1&candidate=exec-2')],
  };
});

function aSide(overrides: Partial<CompareResult['baseline']> = {}): CompareResult['baseline'] {
  return {
    executionId: 'exec-1',
    workloadName: 'average-load',
    serviceVersion: '2.17.0',
    environmentName: 'local',
    requestedAtDisplay: '22 Aug 2026, 09:00',
    ...overrides,
  };
}

function aComparison(overrides: Partial<CompareResult> = {}): CompareResult {
  return {
    baseline: aSide(),
    candidate: aSide({ executionId: 'exec-2', serviceVersion: '2.18.0' }),
    baselineReleaseMissing: false,
    candidateReleaseMissing: false,
    supportsRegressionVerdict: true,
    notComparableExplanation: '',
    differences: [],
    deltas: [{
      metric: 'p95 latency', display: '48 ms → 49 ms', percentChangeDisplay: '+2.1%',
      isDegradation: false, percentChange: 2.1,
    }],
    verdictLabel: 'Unchanged',
    verdictDescription: 'No change large enough to distinguish from run-to-run variance.',
    ...overrides,
  };
}

describe('the compare page', () => {
  it('shows a verdict when the two runs tested the same experiment', () => {
    compareResult = { data: aComparison(), isError: false };
    renderWithProviders(<ComparePage />);

    expect(screen.getByText('Unchanged')).toBeInTheDocument();
    expect(screen.getByText('p95 latency')).toBeInTheDocument();
    expect(screen.getByText('48 ms → 49 ms')).toBeInTheDocument();
  });

  it('declines a verdict and explains why when the experiments differed', () => {
    compareResult = {
      data: aComparison({
        supportsRegressionVerdict: false,
        verdictLabel: null,
        verdictDescription: null,
        notComparableExplanation: 'These two runs did not test the same experiment.',
        differences: ['Different workloads: average-load vs stress-peak'],
      }),
      isError: false,
    };
    renderWithProviders(<ComparePage />);

    expect(screen.getByText('These runs tested different experiments')).toBeInTheDocument();
    expect(
      screen.getByText('Different workloads: average-load vs stress-peak'),
    ).toBeInTheDocument();
  });

  it('flags a missing release rather than silently comparing unnamed builds', () => {
    compareResult = {
      data: aComparison({ baselineReleaseMissing: true }),
      isError: false,
    };
    renderWithProviders(<ComparePage />);

    expect(
      screen.getByText('At least one of these runs did not record its release'),
    ).toBeInTheDocument();
  });
});
