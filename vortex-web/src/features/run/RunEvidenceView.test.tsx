import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../../test/renderWithProviders';
import { phaseFourEvidence } from '../../test/phaseFourEvidence';
import { RunEvidenceView } from './RunEvidenceView';
import type { RunEvidence } from '../../api/run';

function baseEvidence(): RunEvidence {
  return {
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
    requestedAtIso: '2026-08-22T09:00:00Z',
    finishedAtDisplay: '22 Aug 2026, 09:10',
    durationDisplay: '10m',
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
}

function render(evidence: RunEvidence) {
  renderWithProviders(<RunEvidenceView evidence={evidence} serviceId="checkout" />);
}

/**
 * The result page reflects the model, in the order an engineer reads it.
 *
 * <p>These assert the properties that would be quietly lost, not the layout. Most importantly: an
 * invalid run still renders every measurement a valid one does. What changes is the capacity
 * headline and the Experiment block explaining why - Vortex does not go quiet where a number was
 * expected, it replaces the number with the sentence saying why there is not one.
 */
describe('the five blocks', () => {
  it('renders all five, in the order conclusions are read', () => {
    render(baseEvidence());

    const headings = screen
      .getAllByRole('heading', { level: 3 })
      .map((heading) => heading.textContent);

    expect(headings).toEqual(['Load', 'Service', 'Resources', 'Capacity', 'Experiment']);
  });

  it('shows dropped work as unmeasured rather than as zero when the engine reported none', () => {
    render(baseEvidence());

    // The distinction the whole phase turns on, at the surface a reader actually looks at.
    expect(screen.getByText('Not reported by the load generator')).toBeInTheDocument();
  });

  it('says the generator was not observed rather than implying it was healthy', () => {
    render(baseEvidence());

    expect(
      screen.getByText('The machine generating the traffic was not observed')
    ).toBeInTheDocument();
  });

  it('says outcomes were not classified rather than showing an empty table', () => {
    render(baseEvidence());

    expect(screen.getByText('How requests failed was not classified')).toBeInTheDocument();
  });
});

describe('a run that did not measure what it claims to', () => {
  function invalid(): RunEvidence {
    const evidence = baseEvidence();
    return {
      ...evidence,
      validity: {
        grade: 'INVALID',
        label: 'Not valid',
        explanation: 'The experiment did not measure what it claims to. Conclusions are withheld.',
        assessed: true,
        permitsCapacityClaims: false,
        findings: [
          {
            code: 'OFFERED_LOAD_NOT_GENERATED',
            label: 'Offered load was not generated',
            effect: 'WITHHOLDS_CAPACITY',
            statement:
              'The load generator could not start 4812 units of work it was asked to start.',
            fromLevel: '900 requests/sec',
            evidenceIds: ['metric:generator.iterations.dropped'],
          },
        ],
      },
      load: { ...evidence.load, droppedDisplay: '4812', droppedWork: true },
      capacity: {
        ...evidence.capacity,
        sustainableDisplay: '',
        refusal:
          'No sustainable capacity was established. The load generator could not start 4812 units of work.',
      },
    };
  }

  it('states the grade and the finding that produced it', () => {
    render(invalid());

    expect(screen.getByText('Run quality: Not valid')).toBeInTheDocument();
    // Twice, deliberately: the Load block warns inline where the traffic is described, and
    // Experiment explains what the run therefore cannot support. A reader who scrolls to one
    // without the other should still be told.
    expect(
      screen.getAllByText(/could not start 4812 units of work it was asked to start/)
    ).toHaveLength(2);
  });

  it('replaces the capacity headline with the reason there is not one', () => {
    render(invalid());

    expect(screen.getByText('No sustainable capacity was established')).toBeInTheDocument();
  });

  it('still renders every measurement a valid run would', () => {
    render(invalid());

    // Invalidity changes what Vortex states, never what it shows. A page that hid its measurements
    // would make an invalid run look like a failed one.
    expect(screen.getByRole('heading', { level: 3, name: 'Load' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 3, name: 'Service' })).toBeInTheDocument();
    expect(screen.getAllByText(/4812/).length).toBeGreaterThan(0);
  });
});

describe('a run recorded before validity existed', () => {
  it('gains a note rather than losing a number', () => {
    const evidence = baseEvidence();
    render({
      ...evidence,
      validity: { ...evidence.validity, grade: 'NOT_ASSESSED', assessed: false, findings: [] },
    });

    expect(screen.getByText("This run's validity was never assessed")).toBeInTheDocument();
  });
});

describe('resource telemetry', () => {
  it('a run with no resource telemetry artifact renders no chart section at all', () => {
    render(baseEvidence());

    expect(screen.queryByText('Over the run')).not.toBeInTheDocument();
  });

  it('groups a run with resource telemetry by kind, under one heading per kind', () => {
    const evidence = baseEvidence();
    render({
      ...evidence,
      resourceTimeline: {
        present: true,
        completenessStatus: 'COMPLETE',
        completenessReason: '',
        plots: [
          {
            kind: 'CPU',
            kindLabel: 'CPU',
            series: [
              {
                signalId: 'metric:cpu',
                providerId: 'actuator',
                scope: 'SYSTEM_UNDER_TEST',
                scopeLabel: 'System under test',
                seriesLabel: 'CPU utilisation',
                unitSymbol: '%',
                points: [
                  { atIso: '2026-08-22T09:00:00Z', value: 40 },
                  { atIso: '2026-08-22T09:00:05Z', value: 94 },
                ],
                display: '94%',
                limitDisplay: '100%',
                utilisationDisplay: '94%',
                atItsLimit: false,
              },
            ],
          },
        ],
      },
    });

    expect(screen.getByText('Over the run')).toBeInTheDocument();
    expect(screen.getByText('CPU')).toBeInTheDocument();
    expect(screen.getByText(/System under test.*CPU utilisation.*peak 94%/)).toBeInTheDocument();
  });

  it('a partial series is captioned as partial rather than rendered as though it were complete', () => {
    const evidence = baseEvidence();
    render({
      ...evidence,
      resourceTimeline: {
        present: true,
        completenessStatus: 'PARTIAL',
        completenessReason: 'the artifact could not be written to',
        plots: [
          {
            kind: 'CPU',
            kindLabel: 'CPU',
            series: [
              {
                signalId: 'metric:cpu',
                providerId: 'actuator',
                scope: 'SYSTEM_UNDER_TEST',
                scopeLabel: 'System under test',
                seriesLabel: 'CPU utilisation',
                unitSymbol: '%',
                points: [{ atIso: '2026-08-22T09:00:00Z', value: 40 }],
                display: '40%',
                limitDisplay: '',
                utilisationDisplay: '',
                atItsLimit: false,
              },
            ],
          },
        ],
      },
    });

    expect(screen.getByText(/This series is partial/)).toBeInTheDocument();
    expect(screen.getByText(/the artifact could not be written to/)).toBeInTheDocument();
  });
});
