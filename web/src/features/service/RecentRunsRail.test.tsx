import { describe, expect, it } from 'vitest';
import { screen, within } from '@testing-library/react';
import { renderWithProviders } from '../../test/renderWithProviders';
import type { Overview, RunSummary } from '../../api/workspace';
import { RecentRunsRail } from './RecentRunsRail';

function aRun(overrides: Partial<RunSummary> = {}): RunSummary {
  return {
    id: 'run-1',
    verdict: 'PASS',
    verdictLabel: 'Pass',
    stateLabel: 'Completed',
    terminal: true,
    testName: 'capacity-check',
    testType: 'AVERAGE_LOAD',
    testTypeLabel: 'Average load',
    levelDisplay: '50 requests/sec',
    environmentName: 'local',
    classification: 'ISOLATED',
    release: '2.17.0',
    answer: 'Objectives held at 50 requests/sec.',
    p95: '120 ms',
    durationDisplay: '1m',
    relativeTime: '44 minutes ago',
    isoTimestamp: '2026-08-22T04:55:00Z',
    matchesCurrentTest: true,
    differences: [],
    ...overrides,
  };
}

function anOverview(overrides: Partial<Overview> = {}): Overview {
  return {
    header: {
      id: 'checkout',
      name: 'checkout-service',
      description: null,
      target: null,
      environmentCount: 1,
      release: '2.17.0',
      readiness: {
        canRun: true,
        satisfiedCount: 7,
        totalCount: 7,
        blockerCount: 0,
        items: [],
        nextStepText: null,
      },
      operationCount: 4,
      testCount: 1,
      runCount: 1,
      running: null,
    },
    production: null,
    objectives: [],
    capacity: null,
    range: { renderable: false, unit: null, markers: [], openEnded: false },
    latestRun: null,
    tests: [],
    recentRuns: [],
    suggestSmokeTest: false,
    evidencePredatesRelease: false,
    releaseGapText: null,
    evidenceByTestType: [],
    ...overrides,
  };
}

describe('the recent runs rail', () => {
  it('says there are no runs yet, when there are none', () => {
    renderWithProviders(
      <RecentRunsRail overview={anOverview()} serviceId="checkout" />,
    );

    expect(screen.getByText('No runs yet.')).toBeInTheDocument();
  });

  it('opens that exact run\'s own full result on click, never the test\'s current latest run', () => {
    renderWithProviders(
      <RecentRunsRail
        overview={anOverview({ recentRuns: [aRun({ id: 'run-42' })] })}
        serviceId="checkout"
      />,
    );

    expect(screen.getByRole('link', { name: /Pass/ })).toHaveAttribute('href', '/runs/run-42');
  });

  it('names the test once, as a group heading, when every recent run is the same test', () => {
    renderWithProviders(
      <RecentRunsRail
        overview={anOverview({
          recentRuns: [aRun(), aRun({ id: 'run-2', relativeTime: '2 hours ago' })],
        })}
        serviceId="checkout"
      />,
    );

    const section = screen.getByText('Recent runs').closest('section')!;
    expect(within(section).getAllByText('capacity-check')).toHaveLength(1);
  });

  it('names the test on every row once more than one test appears in recent runs', () => {
    renderWithProviders(
      <RecentRunsRail
        overview={anOverview({
          recentRuns: [aRun(), aRun({ id: 'run-2', testName: 'burst-check' })],
        })}
        serviceId="checkout"
      />,
    );

    const section = screen.getByText('Recent runs').closest('section')!;
    expect(within(section).getByText('capacity-check')).toBeInTheDocument();
    expect(within(section).getByText('burst-check')).toBeInTheDocument();
  });

  it('offers "View all" only when there are more runs than shown', () => {
    renderWithProviders(
      <RecentRunsRail
        overview={anOverview({
          header: { ...anOverview().header, runCount: 5 },
          recentRuns: [aRun()],
        })}
        serviceId="checkout"
      />,
    );

    expect(screen.getByRole('link', { name: 'View all' })).toHaveAttribute(
      'href',
      '/services/checkout/runs',
    );
  });

  function manyRuns(count: number): RunSummary[] {
    return Array.from({ length: count }, (_, i) => aRun({ id: `run-${i}`, testName: 'burst-check' }));
  }

  it('shows at most 5 runs, however many the service has', () => {
    renderWithProviders(
      <RecentRunsRail overview={anOverview({ recentRuns: manyRuns(15) })} serviceId="checkout" />,
    );

    expect(screen.getAllByRole('link', { name: /Pass/ })).toHaveLength(5);
  });

  it('shows fewer than 5 when the service has less history than that', () => {
    renderWithProviders(
      <RecentRunsRail overview={anOverview({ recentRuns: manyRuns(3) })} serviceId="checkout" />,
    );

    expect(screen.getAllByRole('link', { name: /Pass/ })).toHaveLength(3);
  });

  it("shows each run's own one-line outcome, not just its pass/fail label", () => {
    renderWithProviders(
      <RecentRunsRail
        overview={anOverview({ recentRuns: [aRun({ answer: 'Objectives held at 50 requests/sec.' })] })}
        serviceId="checkout"
      />,
    );

    expect(screen.getByText('Objectives held at 50 requests/sec.')).toBeInTheDocument();
  });
});
