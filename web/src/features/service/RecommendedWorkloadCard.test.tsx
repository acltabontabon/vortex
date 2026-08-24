import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../test/renderWithProviders';
import type { RecommendationDto } from '../../api/tests';
import { RecommendedWorkloadCard } from './RecommendedWorkloadCard';

let recommendationResult: { data: RecommendationDto | undefined; isError: boolean } = {
  data: undefined,
  isError: false,
};

vi.mock('../../api/tests', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/tests')>();
  return { ...actual, useRecommendationQuery: () => recommendationResult };
});

const RECOMMENDATION: RecommendationDto = {
  type: 'SMOKE',
  model: 'OPEN',
  shapeKind: 'STEADY',
  purpose: 'A very small, steady check that Vortex can reach the service.',
  headline: '10 requests/sec for 30s',
  startLevel: 10,
  durationMinutes: 1,
  explicitStages: [],
  productionInformed: false,
  safetyCeilingApplied: false,
  sourceDescription: 'Manually entered',
  derivation: null,
  availableShapeKinds: ['STEADY'],
};

describe('the recommended workload card', () => {
  it('fails soft — renders nothing — while the recommendation has not loaded', () => {
    recommendationResult = { data: undefined, isError: false };
    renderWithProviders(
      <RecommendedWorkloadCard serviceId="checkout" type="SMOKE" model="OPEN" typeLabel="Smoke" onApply={vi.fn()} />,
    );
    expect(screen.queryByText(/Recommended for/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Use recommended' })).not.toBeInTheDocument();
  });

  it('fails soft — renders nothing — when the recommendation could not be fetched', () => {
    recommendationResult = { data: undefined, isError: true };
    renderWithProviders(
      <RecommendedWorkloadCard serviceId="checkout" type="SMOKE" model="OPEN" typeLabel="Smoke" onApply={vi.fn()} />,
    );
    expect(screen.queryByText(/Recommended for/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Use recommended' })).not.toBeInTheDocument();
  });

  it('renders the purpose and headline exactly as the backend phrased them', () => {
    recommendationResult = { data: RECOMMENDATION, isError: false };
    renderWithProviders(
      <RecommendedWorkloadCard serviceId="checkout" type="SMOKE" model="OPEN" typeLabel="Smoke" onApply={vi.fn()} />,
    );

    expect(screen.getByText('Recommended for Smoke')).toBeInTheDocument();
    expect(
      screen.getByText('A very small, steady check that Vortex can reach the service.'),
    ).toBeInTheDocument();
    expect(screen.getByText('10 requests/sec for 30s')).toBeInTheDocument();
  });

  it('invokes onApply with the fetched recommendation when clicked', async () => {
    recommendationResult = { data: RECOMMENDATION, isError: false };
    const onApply = vi.fn();
    renderWithProviders(
      <RecommendedWorkloadCard serviceId="checkout" type="SMOKE" model="OPEN" typeLabel="Smoke" onApply={onApply} />,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Use recommended' }));

    expect(onApply).toHaveBeenCalledWith(RECOMMENDATION);
  });
});
