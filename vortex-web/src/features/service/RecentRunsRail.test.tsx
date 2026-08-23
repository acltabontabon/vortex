import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import { renderWithProviders } from '../../test/renderWithProviders';
import type { Overview, RunSummary } from '../../api/workspace';
import { RecentRunsRail } from './RecentRunsRail';

// `useElementSize` reports real pixel heights only in a real browser (the test environment's own
// ResizeObserver stub never fires — see src/test/setup.ts) — mocked here so the fit-to-height logic
// itself is actually exercised. The component calls it exactly twice per render, in a fixed order
// (head block, then the first row), so alternating by call count reliably tells the two apart; the
// count is reset before every test since it otherwise keeps accumulating across a whole file's runs.
// Because the component measures one row rather than the whole list divided by count, this mock can
// use fixed heights and still exercise real convergence — nothing here depends on how many rows are
// currently showing, same as the production code it stands in for.
const elementHeights = { head: 0, row: 0 };
const callState = { count: 0 };
vi.mock('@mantine/hooks', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@mantine/hooks')>();
  return {
    ...actual,
    useElementSize: () => {
      const isHead = callState.count % 2 === 0;
      callState.count += 1;
      return { ref: () => {}, width: 0, height: isHead ? elementHeights.head : elementHeights.row };
    },
  };
});

beforeEach(() => {
  callState.count = 0;
  elementHeights.head = 0;
  elementHeights.row = 0;
});

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

  it('shows only the minimum when the columns are not genuinely side by side', () => {
    renderWithProviders(
      <RecentRunsRail
        overview={anOverview({ recentRuns: manyRuns(15) })}
        serviceId="checkout"
        fitHeight={null}
      />,
    );

    expect(screen.getAllByRole('link', { name: /Pass/ })).toHaveLength(5);
  });

  it('grows past the minimum to use the space beside a taller Tests column', () => {
    // One row measures to 50px; with the 4px gap between rows, that's 54px per row. A 500px Tests
    // column minus a 40px head block leaves room for 8 rows (floor(460 / 54)). Because the estimate
    // comes from a single row rather than the whole list divided by count, this converges to an
    // exact, stable number instead of drifting — that stability is the point of this test.
    elementHeights.head = 40;
    elementHeights.row = 50;

    renderWithProviders(
      <RecentRunsRail
        overview={anOverview({ recentRuns: manyRuns(15) })}
        serviceId="checkout"
        fitHeight={500}
      />,
    );

    expect(screen.getAllByRole('link', { name: /Pass/ })).toHaveLength(8);
  });

  it('never shows more rows than the service actually has, however tall Tests is', () => {
    elementHeights.head = 40;
    elementHeights.row = 50;

    renderWithProviders(
      <RecentRunsRail
        overview={anOverview({ recentRuns: manyRuns(6) })}
        serviceId="checkout"
        fitHeight={5000}
      />,
    );

    expect(screen.getAllByRole('link', { name: /Pass/ })).toHaveLength(6);
  });
});
