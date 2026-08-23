import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../../test/renderWithProviders';
import type { Run } from '../../api/run';
import { RunReportPage } from './RunReportPage';
import { phaseFourEvidence } from '../../test/phaseFourEvidence';

let queryResult: { data: Run | undefined; isError: boolean } = { data: undefined, isError: false };

vi.mock('../../api/run', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/run')>();
  return { ...actual, useRunQuery: () => queryResult };
});

vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  return { ...actual, useParams: () => ({ id: 'exec-1' }) };
});

const evidence: NonNullable<Run['evidence']> = {
  ...phaseFourEvidence(),
  identity: {
    executionId: 'exec-1',
    shortId: 'exec-1',
    serviceName: 'checkout-service',
    serviceVersion: '2.17.0',
    workloadName: 'average-load',
    testTypeLabel: 'Average load',
    environmentName: 'local',
    environmentTypeLabel: 'Local',
    classification: 'ISOLATED',
    classificationLabel: 'Isolated',
    targetUrl: 'https://checkout.internal',
    targetWasRewritten: false,
    targetRewriteReason: null,
    targetKind: 'EXTERNAL_ENDPOINT',
    targetSummary: 'https://checkout.internal',
    targetOwnershipLabel: 'Externally managed',
    resourceSummary: null,
    requestedAtIso: '2026-08-22T09:00:00Z',
    finishedAtDisplay: '22 Aug 2026, 09:10',
    durationDisplay: '10m',
    testType: 'AVERAGE_LOAD',
  },
  verdict: {
    question: 'Can the service sustain the traffic it typically receives?',
    verdict: 'PASS',
    verdictLabel: 'Met',
    answer: 'Yes. The service met every objective.',
    qualifications: [],
  },
  workload: {
    open: true,
    modelLabel: 'Arrival rate',
    modelGuidance: '',
    configuredPeakDisplay: '50 requests/sec',
    sourceDescribe: 'Entered by hand',
    achievedRateDisplay: '50 requests/sec',
    deliveredPercent: '100%',
    fellShort: false,
    deliveredCaveat: null,
    requestsDisplay: '30,000',
    estimatedRequestsDisplay: null,
    errorRateDisplay: '0%',
    failuresDisplay: '0',
    configuredDurationDisplay: '10m',
    actualDurationDisplay: '10m',
    operationMix: ['getOrder 100%'],
    scriptSourceLabel: 'Generated',
  },
  performance: {
    latencyRows: [{ percentileLabel: 'p95', durationDisplay: '210 ms' }],
    maxLatencyDisplay: '320 ms',
    hasLimitsCard: false,
    sloBreakpointDisplay: null,
    sloBreakpointStrengthLabel: null,
    sloBreakpointStagesText: null,
    systemSaturationDescribe: null,
    systemSaturationExplanation: null,
    headroomDisplay: null,
    headroomRefusal: null,
    baselineQuality: [],
  },
  acceptance: { hasObjectives: true, results: [], absenceExplanation: null },
  hasOperationBreakdown: false,
  operations: [],
  loadAxis: {
    renderable: false,
    svg: null,
    drawsBoundary: false,
    drawsSaturation: false,
    highestCompliantDisplay: null,
    firstNonCompliantDisplay: null,
    boundaryStatement: null,
    saturationDescribe: null,
    testedToDisplay: null,
  },
  timeline: { present: false, plots: [], stages: [], showsDerivedCaveat: false, tableRows: [], breakpointAtIso: null, levelChangeAtIso: null },
  observability: { present: false, signals: [], providersConsulted: [], gaps: [] },
  hasFindings: false,
  findings: [],
  comparison: null,
  provenance: {
    vortexVersion: '1.0.0',
    engineVersion: 'k6 0.50',
    runtimeVersion: 'Java 25',
    dockerImage: null,
    configurationHash: 'abc123',
    secretReferences: [],
    artifactDirectory: '/tmp/exec-1',
    reproductionCommand: 'vortex run average-load',
    hasArtifacts: false,
    artifactNames: [],
  },
  releaseMoved: false,
  previousCompatibleExecutionId: null,
};

function aRun(overrides: Partial<Run> = {}): Run {
  return {
    executionId: 'exec-1',
    running: false,
    terminal: true,
    stateLabel: 'Completed',
    plan: {
      projectId: 'checkout',
      projectName: 'checkout-service',
      testTypeLabel: 'Average load',
      testTypeQuestion: 'Can the service sustain the traffic it typically receives?',
      workloadName: 'average-load',
      environmentName: 'local',
      targetDisplay: 'https://checkout.internal',
      environmentTypeLabel: 'Local',
      workloadModelLabel: 'Arrival rate',
      peakLevelDisplay: '50 requests/sec',
      singleOperation: false,
      operationsSummary: 'getOrder',
      classification: 'ISOLATED',
      classificationLabel: 'Isolated',
      classificationCaveat: '',
      totalDurationDisplay: '10m',
    },
    progress: null,
    requestedAtDisplay: '22 Aug 2026, 09:00',
    startedAtDisplay: '22 Aug 2026, 09:00',
    failed: false,
    failureLabel: null,
    failureGuidance: null,
    failureDetail: null,
    cancelled: false,
    evidence: null,
    ...overrides,
  };
}

describe('the run report page', () => {
  it('renders the evidence with no live controls, no AI panel', () => {
    queryResult = { data: aRun({ evidence }), isError: false };
    renderWithProviders(<RunReportPage />);

    expect(screen.getByText('Yes. The service met every objective.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel run' })).not.toBeInTheDocument();
    expect(screen.queryByText('AI interpretation')).not.toBeInTheDocument();
  });

  it('says the run has not finished, rather than rendering an empty report', () => {
    queryResult = { data: aRun({ terminal: false, evidence: null }), isError: false };
    renderWithProviders(<RunReportPage />);

    expect(screen.getByText('This run has not finished yet, so there is no report to show.')).toBeInTheDocument();
  });
});
