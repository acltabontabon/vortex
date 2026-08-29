import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../../../test/renderWithProviders';
import type { SanityFindingDto } from '../../../api/thresholds';
import { ThresholdRow } from './ThresholdRow';

vi.mock('../../../api/thresholds', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/thresholds')>();
  return {
    ...actual,
    useThresholdRecommendationQuery: () => ({ data: undefined, isLoading: false, isError: false }),
  };
});

function renderRow(overrides: Partial<Parameters<typeof ThresholdRow>[0]> = {}) {
  renderWithProviders(
    <ThresholdRow
      label="P95 latency"
      unit="ms"
      value={550}
      onChange={() => {}}
      comparisonText={null}
      findings={[]}
      serviceId="checkout"
      workload="average_load"
      metric="LATENCY"
      percentile={95}
      provenance={null}
      onApplyRecommendation={() => {}}
      {...overrides}
    />
  );
}

describe('ThresholdRow', () => {
  it('shows the field and the Help me choose trigger', () => {
    renderRow();

    expect(screen.getByLabelText('P95 latency')).toHaveValue('550 ms');
    expect(screen.getByRole('button', { name: 'Help me choose' })).toBeInTheDocument();
  });

  it('shows the live comparison text when supplied', () => {
    renderRow({ comparisonText: '52% stricter than current production behavior.' });

    expect(screen.getByText('52% stricter than current production behavior.')).toBeInTheDocument();
  });

  it('shows no comparison text when none is supplied', () => {
    renderRow({ comparisonText: null });

    expect(screen.queryByText(/stricter than|looser than|roughly matches/)).not.toBeInTheDocument();
  });

  it('surfaces the worst finding for this threshold', () => {
    const findings: SanityFindingDto[] = [
      { severity: 'CAUTION', thresholdId: 'latency.p95', message: 'noticeably stricter than production' },
      { severity: 'INVALID', thresholdId: 'latency.p95', message: 'significantly stricter than production' },
    ];

    renderRow({ findings });

    expect(screen.getByText('significantly stricter than production')).toBeInTheDocument();
    expect(screen.queryByText('noticeably stricter than production')).not.toBeInTheDocument();
  });

  it('shows no evidence card while the field is empty', () => {
    renderRow({ value: '' });

    expect(screen.queryByText(/Manual objective/)).not.toBeInTheDocument();
  });

  it('shows the evidence card once a value is set', () => {
    renderRow({ value: 550, provenance: null });

    expect(screen.getByText('Manual objective — no supporting evidence recorded.')).toBeInTheDocument();
  });
});
