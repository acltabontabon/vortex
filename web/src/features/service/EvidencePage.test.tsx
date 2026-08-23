import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../../test/renderWithProviders';
import type { Evidence } from '../../api/workspace';
import { EvidencePage } from './EvidencePage';

/**
 * What the deleted Thymeleaf `CapacityHistoryPageTest` asserted about wording, ported to what
 * replaced it: the domain's careful phrasing must survive the move to a JSON API and a React
 * component just as literally as it survived Thymeleaf's own accessor resolution.
 */

let queryResult: { data: Evidence | undefined; isError: boolean } = {
  data: undefined,
  isError: false,
};

vi.mock('../../api/workspace', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/workspace')>();
  return { ...actual, useEvidenceQuery: () => queryResult };
});

vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  return { ...actual, useParams: () => ({ id: 'checkout' }) };
});

function anEvidence(overrides: Partial<Evidence> = {}): Evidence {
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
      runCount: 3,
      running: null,
    },
    capacity: null,
    range: { renderable: false, unit: null, markers: [], openEnded: false },
    headroomLabel: null,
    production: null,
    releaseMoved: false,
    history: [],
    runs: [],
    ...overrides,
  };
}

function aCapacity(overrides: Partial<Evidence['capacity']> = {}): NonNullable<Evidence['capacity']> {
  return {
    compliantLevel: '50 requests/sec',
    label: 'Tested SLO-compliant capacity',
    boundary: '50 requests/sec compliant; no tested level failed',
    boundaryLabel: 'Tested capacity boundary',
    quotable: true,
    boundaryStatus: 'FAR_EDGE_NOT_REACHED',
    boundaryStatusLabel: 'far edge not reached',
    boundaryStrength: 'Low',
    firstNonCompliant: null,
    headroom: null,
    headroomRefusal: 'This capacity was measured in an isolated test.',
    serviceVersion: '2.17.0',
    environmentName: 'local',
    classification: 'ISOLATED',
    dependencyMode: 'MOCKED',
    workloadName: 'capacity-check',
    operationMix: ['getOrder 100%'],
    objectives: ['p95 < 500 ms'],
    durationDisplay: '20m',
    measuredAt: '22 Aug 2026, 04:55',
    runId: 'exec-1',
    conditions: ['Service version: 2.17.0', 'Dependencies: Mocked', 'Operation mix: getOrder 100%'],
    constraintCandidates: [],
    ...overrides,
  };
}

describe('the evidence page', () => {
  it('renders headroom refusal as the reason, never as a dash or silence', () => {
    queryResult = { data: anEvidence({ capacity: aCapacity() }), isError: false };
    renderWithProviders(<EvidencePage />);

    expect(screen.getByText('Not computed')).toBeInTheDocument();
    expect(
      screen.getByText('This capacity was measured in an isolated test.'),
    ).toBeInTheDocument();
  });

  it('states no capacity has been established, rather than showing an empty table', () => {
    queryResult = { data: anEvidence({ capacity: null }), isError: false };
    renderWithProviders(<EvidencePage />);

    expect(
      screen.getByText('No tested capacity has been established for this service.'),
    ).toBeInTheDocument();
  });

  it('uses the domain\'s own conditions sentences verbatim', () => {
    queryResult = { data: anEvidence({ capacity: aCapacity() }), isError: false };
    renderWithProviders(<EvidencePage />);

    expect(screen.getByText('Dependencies: Mocked')).toBeInTheDocument();
  });

  it('frames a constraint candidate as correlation, never as a cause', () => {
    queryResult = {
      data: anEvidence({
        capacity: aCapacity({
          constraintCandidates: [
            {
              describe:
                'Connection pool utilisation was at 98% where objectives stopped being met. '
                + 'This run establishes that they coincided, not that this resource produced the '
                + 'degradation.',
              strengthLabel: 'Medium',
              support: 'Support: Medium, stage boundaries derived from the plan.',
            },
          ],
        }),
      }),
      isError: false,
    };
    renderWithProviders(<EvidencePage />);

    expect(
      screen.getByText(/not that this resource produced the degradation/),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/These are candidates, not causes/),
    ).toBeInTheDocument();
  });

  it('never labels a constraint candidate with boundary confidence', () => {
    // "High" beside a resource name reads as "this is the cause, with high confidence" — a claim
    // no run supports. Boundary strength must not appear anywhere near the candidates list.
    queryResult = {
      data: anEvidence({
        capacity: aCapacity({
          boundaryStrength: 'High',
          constraintCandidates: [
            { describe: 'Pool was at 98%.', strengthLabel: 'Medium', support: 'Support: Medium.' },
          ],
        }),
      }),
      isError: false,
    };
    renderWithProviders(<EvidencePage />);

    const candidatesSection = screen.getByText('What was near its limit there').closest('details');
    expect(candidatesSection).not.toBeNull();
    expect(candidatesSection!.textContent).not.toContain('High');
  });
});
