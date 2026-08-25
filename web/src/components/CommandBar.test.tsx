import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../test/renderWithProviders';
import type { ServiceCard, WorkloadRef } from '../api/home';
import { CommandBar } from './CommandBar';

function workload(name: string, testType: string): WorkloadRef {
  return { name, testType, testTypeLabel: testType, productionInformed: false };
}

function card(overrides: Partial<ServiceCard> = {}): ServiceCard {
  return {
    id: 'checkout',
    name: 'checkout-service',
    description: null,
    running: false,
    runningRun: null,
    canRun: true,
    blockers: [],
    nextStepText: null,
    workloadTestTypeLabel: null,
    workloadProductionInformed: null,
    workloads: [workload('breakpoint-ramp', 'BREAKPOINT'), workload('steady', 'AVERAGE_LOAD')],
    apiImported: true,
    objectivesConfigured: true,
    productionObserved: false,
    recentTerminalRunCount: 3,
    updatedAtRelative: 'just now',
    updatedAtIso: '2026-01-01T00:00:00Z',
    headroomDisplay: null,
    latestVerdict: null,
    rangeMarkers: [],
    satisfiedCount: 7,
    totalCount: 7,
    evidencePredatesRelease: false,
    releaseGapText: null,
    ...overrides,
  };
}

/**
 * A control surface states what it will do before you press it. These assert that each command
 * carries its own resolved sub-line, and that a command you cannot press is still announced as a
 * control rather than quietly becoming inert text.
 */
describe('CommandBar', () => {
  it('gives each command its own account of what it will do', () => {
    renderWithProviders(<CommandBar service={card()} />);

    // Four labels, four *different* sub-lines — the failure this component exists to fix was four
    // commands that all did the same thing.
    expect(screen.getByText('Run breakpoint-ramp')).toBeInTheDocument();
    expect(screen.getByText('Run steady')).toBeInTheDocument();
    expect(screen.getByText('Pick two of 3 recent runs')).toBeInTheDocument();
    expect(screen.getByText('Record what it receives')).toBeInTheDocument();
  });

  it('names every command accessibly, without relying on its glyph', () => {
    renderWithProviders(<CommandBar service={card()} />);

    for (const label of ['Find its limit', 'Validate capacity', 'Compare runs', 'Use production traffic']) {
      expect(screen.getByRole('link', { name: new RegExp(label) })).toBeInTheDocument();
    }
  });

  describe('a command that cannot be pressed', () => {
    const blocked = card({ workloads: [], apiImported: false });

    it('is not a link', () => {
      renderWithProviders(<CommandBar service={blocked} />);

      expect(screen.queryByRole('link', { name: /Find its limit/ })).not.toBeInTheDocument();
    });

    it('is still a control, and one that is out of the tab order', () => {
      renderWithProviders(<CommandBar service={blocked} />);

      expect(screen.getByRole('button', { name: /Find its limit/ })).toBeDisabled();
    });

    it('says why on the page, not only to whoever hovers it', () => {
      renderWithProviders(<CommandBar service={blocked} />);

      // Both run-shaped commands are blocked by the same missing catalog, and each states it on
      // its own control — a reason belongs to the command it explains, not to the row.
      const reasons = screen.getAllByText('Import an OpenAPI document first');
      expect(reasons).toHaveLength(2);
      reasons.forEach((reason) => expect(reason).toBeVisible());
    });
  });

  it('offers a service that cannot run only the thing it can actually do', () => {
    renderWithProviders(
      <CommandBar service={card({ canRun: false, blockers: ['Workload defined'] })} />,
    );

    const links = screen.getAllByRole('link');
    expect(links).toHaveLength(1);
    expect(links[0]).toHaveAttribute('href', '/services/checkout/configuration');
    expect(links[0]).toHaveTextContent('Continue setup');
  });
});
