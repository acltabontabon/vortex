import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../../test/renderWithProviders';
import type { TestRow as Test } from '../../api/workspace';
import { TestDetailsDrawer } from './TestDetailsDrawer';

function aTest(overrides: Partial<Test> = {}): Test {
  return {
    name: 'capacity-check',
    description: null,
    question: 'Does the service meet its objectives under normal traffic?',
    testType: 'AVERAGE_LOAD',
    testTypeLabel: 'Average load',
    testTypeQuestion: 'Does the service meet its objectives under normal traffic?',
    saturating: false,
    model: 'OPEN',
    modelLabel: 'Arrival rate',
    levelDisplay: '50 requests/sec',
    levelUnit: 'requests/sec',
    durationDisplay: '1m',
    stageCount: 1,
    ramping: false,
    operationCount: 4,
    source: {
      kind: 'MANUAL',
      label: 'Manually entered',
      describe: 'Manually entered',
      detail: null,
      productionInformed: false,
      observedWindow: null,
      derivation: null,
    },
    versusProduction: null,
    runnable: true,
    problems: [],
    environmentName: 'local',
    latestRun: null,
    runCount: 0,
    drift: null,
    composition: [],
    compositionDrift: null,
    capacity: null,
    range: { renderable: false, unit: null, markers: [], openEnded: false },
    ...overrides,
  };
}

describe('the test details drawer', () => {
  it('states the definition and the workload, with the operation count this time', () => {
    renderWithProviders(
      <TestDetailsDrawer test={aTest()} opened onClose={() => {}} />,
    );

    expect(screen.getByText('capacity-check')).toBeInTheDocument();
    expect(
      screen.getByText('Does the service meet its objectives under normal traffic?'),
    ).toBeInTheDocument();
    expect(screen.getByText('50 req/s')).toBeInTheDocument();
    expect(screen.getByText('4')).toBeInTheDocument();
    expect(screen.getByText('Operations')).toBeInTheDocument();
  });

  it('shows the traffic distribution when the test has one', () => {
    renderWithProviders(
      <TestDetailsDrawer
        test={aTest({
          composition: [
            {
              operationId: 'op-1',
              label: 'List orders',
              method: 'GET',
              path: '/orders',
              sharePercent: '100%',
              shareFraction: 1,
              rateDisplay: '50',
              known: true,
            },
          ],
        })}
        opened
        onClose={() => {}}
      />,
    );

    expect(screen.getByText('Traffic')).toBeInTheDocument();
    expect(screen.getByText('/orders')).toBeInTheDocument();
  });

  it('omits the traffic distribution section when the test has none', () => {
    renderWithProviders(
      <TestDetailsDrawer test={aTest({ composition: [] })} opened onClose={() => {}} />,
    );

    expect(screen.queryByText('Traffic distribution')).not.toBeInTheDocument();
  });

  it('states the full provenance sentence, including the production comparison', () => {
    renderWithProviders(
      <TestDetailsDrawer
        test={aTest({
          source: {
            kind: 'DERIVED_FROM_OBSERVATION',
            label: 'Derived from observed production traffic',
            describe: 'Derived from observed production traffic',
            detail: 'Dynatrace',
            productionInformed: true,
            observedWindow: '1–7 Aug',
            derivation: 'observed peak 35 × 1.5 = 53',
          },
          versusProduction: '1.43× observed production peak',
        })}
        opened
        onClose={() => {}}
      />,
    );

    expect(
      screen.getByText(
        'Derived from observed production traffic — Dynatrace · 1.43× observed production peak',
      ),
    ).toBeInTheDocument();
    expect(screen.getByText('observed peak 35 × 1.5 = 53')).toBeInTheDocument();
  });

  it('omits the derivation line when the domain provided none', () => {
    renderWithProviders(<TestDetailsDrawer test={aTest()} opened onClose={() => {}} />);

    expect(screen.queryByText(/×/)).not.toBeInTheDocument();
  });

  it('never shows the latest result — that moved to the inspector, this drawer is definition only', () => {
    renderWithProviders(
      <TestDetailsDrawer
        test={aTest({
          latestRun: {
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
            relativeTime: '12 minutes ago',
            isoTimestamp: '2026-08-22T04:55:00Z',
            matchesCurrentTest: true,
            differences: [],
          },
        })}
        opened
        onClose={() => {}}
      />,
    );

    expect(screen.queryByText('Latest result')).not.toBeInTheDocument();
    expect(screen.queryByText('Objectives held at 50 requests/sec.')).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'View full result →' })).not.toBeInTheDocument();
  });
});
