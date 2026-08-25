import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../test/renderWithProviders';
import type { HomeResponse, ServiceCard } from '../api/home';
import { Home } from './Home';

function card(id: string, name: string): ServiceCard {
  return {
    id,
    name,
    description: null,
    running: false,
    runningRun: null,
    canRun: true,
    blockers: [],
    nextStepText: null,
    workloadTestTypeLabel: null,
    workloadProductionInformed: null,
    workloads: [{ name: 'steady', testType: 'AVERAGE_LOAD', testTypeLabel: 'Average load', productionInformed: false }],
    apiImported: true,
    objectivesConfigured: true,
    productionObserved: false,
    recentTerminalRunCount: 2,
    updatedAtRelative: 'just now',
    updatedAtIso: '2026-01-01T00:00:00Z',
    headroomDisplay: null,
    latestVerdict: null,
    rangeMarkers: [],
    satisfiedCount: 7,
    totalCount: 7,
    evidencePredatesRelease: false,
    releaseGapText: null,
  };
}

const data: HomeResponse = { cards: [card('checkout', 'checkout-service'), card('payments', 'payment-service')] };

vi.mock('../api/home', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/home')>();
  return { ...actual, useHomeQuery: () => ({ data, isError: false }) };
});

/**
 * Which service you are working on is part of where you are, not hidden component state: it
 * survives a link out to a composer and back, and a link to a service that no longer exists
 * degrades to nothing selected rather than to a command bar addressing a ghost.
 */
describe('Home', () => {
  it('opens with a service already selected when the URL names one', () => {
    renderWithProviders(<Home />, { route: '/?service=payments' });

    expect(screen.getByText('What do you want to test?')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Compare runs/ })).toHaveAttribute(
      'href',
      '/runs?project=payments',
    );
  });

  it('opens with nothing selected', () => {
    renderWithProviders(<Home />, { route: '/' });

    expect(screen.queryByText('What do you want to test?')).not.toBeInTheDocument();
  });

  it('forgets a service that no longer exists rather than showing commands for it', () => {
    renderWithProviders(<Home />, { route: '/?service=deleted-last-week' });

    expect(screen.queryByText('What do you want to test?')).not.toBeInTheDocument();
  });

  it('records the selection, and clears it when the same card is picked again', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Home />, { route: '/' });

    await user.click(screen.getByRole('button', { name: /checkout-service/ }));
    expect(screen.getByRole('link', { name: /Compare runs/ })).toHaveAttribute(
      'href',
      '/runs?project=checkout',
    );

    await user.click(screen.getByRole('button', { name: /checkout-service/ }));
    expect(screen.queryByText('What do you want to test?')).not.toBeInTheDocument();
  });
});
