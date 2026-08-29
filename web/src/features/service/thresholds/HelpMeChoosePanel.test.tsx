import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../../test/renderWithProviders';
import type { ThresholdRecommendationPanelDto } from '../../../api/thresholds';
import { HelpMeChoosePanel } from './HelpMeChoosePanel';

let queryResult: {
  data: ThresholdRecommendationPanelDto | undefined;
  isLoading: boolean;
  isError: boolean;
} = { data: undefined, isLoading: false, isError: false };

vi.mock('../../../api/thresholds', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/thresholds')>();
  return { ...actual, useThresholdRecommendationQuery: () => queryResult };
});

beforeEach(() => {
  queryResult = { data: undefined, isLoading: false, isError: false };
});

function aPanel(overrides: Partial<ThresholdRecommendationPanelDto> = {}): ThresholdRecommendationPanelDto {
  return {
    production: null,
    baselines: [],
    recommendations: [],
    ...overrides,
  };
}

describe('HelpMeChoosePanel', () => {
  it('shows an honest empty state when there is no evidence at all', () => {
    queryResult = { data: aPanel(), isLoading: false, isError: false };

    renderWithProviders(
      <HelpMeChoosePanel serviceId="checkout" workload="average_load" metric="LATENCY" percentile={95} onApply={() => {}} />
    );

    expect(screen.getByText(/No baseline yet/)).toBeInTheDocument();
  });

  it('fails soft when evidence cannot be fetched, without blocking manual entry', () => {
    queryResult = { data: undefined, isLoading: false, isError: true };

    renderWithProviders(
      <HelpMeChoosePanel serviceId="checkout" workload="average_load" metric="LATENCY" percentile={95} onApply={() => {}} />
    );

    expect(screen.getByText(/you can still type a value directly/)).toBeInTheDocument();
  });

  it('shows production and baseline evidence with their quality', () => {
    queryResult = {
      data: aPanel({
        production: {
          displayValue: '620 ms',
          rawValue: 620,
          sourceLabel: 'prometheus',
          window: 'last 30 days',
          evidenceQuality: 'STRONG',
          stale: false,
          runQuality: null,
          executionId: null,
        },
        baselines: [
          {
            displayValue: '510 ms',
            rawValue: 510,
            sourceLabel: 'run exec-184',
            window: '',
            evidenceQuality: 'MODERATE',
            stale: false,
            runQuality: 'Valid',
            executionId: 'exec-184',
          },
        ],
      }),
      isLoading: false,
      isError: false,
    };

    renderWithProviders(
      <HelpMeChoosePanel serviceId="checkout" workload="average_load" metric="LATENCY" percentile={95} onApply={() => {}} />
    );

    expect(screen.getByText('620 ms')).toBeInTheDocument();
    expect(screen.getByText('510 ms')).toBeInTheDocument();
    expect(screen.getByText('Strong evidence')).toBeInTheDocument();
    expect(screen.getByText('Moderate evidence')).toBeInTheDocument();
  });

  it('applying a recommendation reports its raw value, label and source', async () => {
    queryResult = {
      data: aPanel({
        recommendations: [
          {
            label: 'Balanced',
            source: 'VORTEX_BASELINE',
            sourceLabel: 'Vortex baseline',
            displayValue: '575 ms',
            rawValue: 575,
            derivation: 'Your best valid baseline of 510 ms x 1.10 = 561 ms, rounded to 575 ms.',
            evidenceQuality: 'MODERATE',
          },
        ],
      }),
      isLoading: false,
      isError: false,
    };
    const onApply = vi.fn();

    renderWithProviders(
      <HelpMeChoosePanel serviceId="checkout" workload="average_load" metric="LATENCY" percentile={95} onApply={onApply} />
    );

    await userEvent.click(screen.getByRole('button', { name: 'Use 575 ms' }));

    expect(onApply).toHaveBeenCalledWith(
      expect.objectContaining({
        label: 'Balanced',
        source: 'VORTEX_BASELINE',
        rawValue: 575,
        derivation: 'Your best valid baseline of 510 ms x 1.10 = 561 ms, rounded to 575 ms.',
        evidenceQuality: 'MODERATE',
      })
    );
  });
});
