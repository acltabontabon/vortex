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
    reproductionCommand: 'workload average-load',
    hasArtifacts: false,
    artifactNames: [],
  },
  releaseMoved: false,
  previousCompatibleExecutionId: null,
  };
}

function render(evidence: RunEvidence) {
  renderWithProviders(
    <RunEvidenceView
      evidence={evidence}
      serviceId="checkout"
      executionId="exec-1"
      runAgainHref="/services/checkout/run"
    />,
  );
}

/**
 * The result page reflects the model, in the order an engineer reads it.
 *
 * <p>These assert the properties that would be quietly lost, not the layout. Most importantly: an
 * invalid run still renders every measurement a valid one does. What changes is the capacity
 * headline and the evidence-quality block explaining why - Vortex does not go quiet where a number
 * was expected, it replaces the number with the sentence saying why there is not one.
 */
describe('the evidence sections', () => {
  it('renders in the order conclusions are read', () => {
    render(baseEvidence());

    const headings = screen
      .getAllByRole('heading', { level: 2 })
      .map((heading) => heading.textContent);

    expect(headings).toEqual([
      'What Vortex learned',
      'Performance',
      'Objectives',
      'Resources',
      'Capacity',
      'Evidence & provenance',
    ]);
  });

  it('shows dropped work as unmeasured rather than as zero when the engine reported none', () => {
    render(baseEvidence());

    // The distinction the whole phase turns on, at the surface a reader actually looks at.
    expect(screen.getByText('Not reported by the load generator')).toBeInTheDocument();
  });

  it('says the generator was not observed rather than implying it was healthy', () => {
    render(baseEvidence());

    expect(
      screen.getByText("The generator's own process or container was not observed"),
    ).toBeInTheDocument();
    expect(
      screen.getByText('The machine running the load generator was not observed'),
    ).toBeInTheDocument();
  });

  it('says outcomes were not classified rather than showing an empty table', () => {
    render(baseEvidence());

    expect(screen.getByText('How requests failed was not classified')).toBeInTheDocument();
  });

  it('states the verdict answer once, in the Result block', () => {
    render(baseEvidence());

    expect(screen.getByText('Yes. The service met every objective.')).toBeInTheDocument();
  });
});

describe('the run identity\'s target facts', () => {
  it('shows the target ownership, and omits the redundant kind/summary fact for an external endpoint', () => {
    render(baseEvidence());

    expect(screen.getByText('Externally managed')).toBeInTheDocument();
    expect(screen.queryByText('Target kind')).not.toBeInTheDocument();
    expect(screen.queryByText('Target resources')).not.toBeInTheDocument();
  });

  it('shows the target kind/summary and resource envelope for a Docker-managed run', () => {
    const evidence = baseEvidence();
    evidence.identity = {
      ...evidence.identity,
      targetKind: 'DOCKER_IMAGE',
      targetSummary: 'Docker: payment-service:1.4.2',
      targetOwnershipLabel: 'Vortex managed',
      resourceSummary: '0.5 CPU · 512 MiB',
    };
    render(evidence);

    expect(screen.getByText('Docker: payment-service:1.4.2')).toBeInTheDocument();
    expect(screen.getByText('Vortex managed')).toBeInTheDocument();
    expect(screen.getByText('0.5 CPU · 512 MiB')).toBeInTheDocument();
  });

  it('omits the resource envelope fact for a run with no confirmed envelope', () => {
    const evidence = baseEvidence();
    evidence.identity = { ...evidence.identity, resourceSummary: null };
    render(evidence);

    expect(screen.queryByText('Target resources')).not.toBeInTheDocument();
  });

  it('leaves the engine\'s own Docker-image provenance fact completely unaffected by the target facts', () => {
    // A direct regression assertion: EvidenceProvenance.dockerImage is a distinct concept (the k6
    // engine's own container) from the identity facts above (the run's *target*), and the two must
    // never be conflated.
    const evidence = baseEvidence();
    evidence.identity = {
      ...evidence.identity,
      targetKind: 'DOCKER_IMAGE',
      targetSummary: 'Docker: payment-service:1.4.2',
      targetOwnershipLabel: 'Vortex managed',
      resourceSummary: '0.5 CPU · 512 MiB',
    };
    evidence.provenance = { ...evidence.provenance, dockerImage: null };
    render(evidence);

    expect(evidence.provenance.dockerImage).toBeNull();
    expect(screen.getByText('Docker: payment-service:1.4.2')).toBeInTheDocument();
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

    expect(screen.getByText('Not valid')).toBeInTheDocument();
    expect(
      screen.getAllByText('The experiment did not measure what it claims to. Conclusions are withheld.').length
    ).toBeGreaterThan(0);
    // Twice, deliberately: Performance warns inline where the traffic is described, and Evidence
    // quality explains what the run therefore cannot support. A reader who scrolls to one without
    // the other should still be told.
    expect(
      screen.getAllByText(/could not start 4812 units of work it was asked to start/)
    ).toHaveLength(2);
  });

  it('replaces the capacity headline with the reason there is not one', () => {
    render(invalid());

    // Shown both as the capacity section's own headline and as the key-metrics tile's value.
    expect(screen.getAllByText('Not established').length).toBeGreaterThan(0);
  });

  it('still renders every measurement a valid run would', () => {
    render(invalid());

    // Invalidity changes what Vortex states, never what it shows. A page that hid its measurements
    // would make an invalid run look like a failed one.
    expect(screen.getByRole('heading', { level: 2, name: 'Performance' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Resources' })).toBeInTheDocument();
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
  it('a run with no resource telemetry artifact renders no timeline track for it', () => {
    render(baseEvidence());

    expect(screen.queryByText('Run timeline')).not.toBeInTheDocument();
  });

  it('groups a run with resource telemetry by kind, on the resources page', () => {
    const evidence = baseEvidence();
    render({
      ...evidence,
      timeline: { ...evidence.timeline, present: true },
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
                utilisationFraction: 0.94,
                limitValue: null,
              },
            ],
          },
        ],
      },
    });

    expect(screen.getByText('Run timeline')).toBeInTheDocument();
    expect(screen.getByText('CPU — system under test')).toBeInTheDocument();
  });

  it('draws only the system under test on the main timeline — the load generator has its own place, one click away', () => {
    const evidence = baseEvidence();
    render({
      ...evidence,
      timeline: { ...evidence.timeline, present: true },
      resourceTimeline: {
        present: true,
        completenessStatus: 'COMPLETE',
        completenessReason: '',
        plots: [
          {
            kind: 'MEMORY',
            kindLabel: 'Memory',
            series: [
              {
                signalId: 'metric:docker.memory.used',
                providerId: 'docker',
                scope: 'SYSTEM_UNDER_TEST',
                scopeLabel: 'System under test',
                seriesLabel: 'Container memory',
                unitSymbol: 'bytes',
                points: [{ atIso: '2026-08-22T09:00:00Z', value: 200_000_000 }],
                display: '190.7 MB',
                limitDisplay: '512 MB',
                utilisationDisplay: '37%',
                atItsLimit: false,
                utilisationFraction: 0.37,
                limitValue: null,
              },
              {
                signalId: 'metric:generator.process.memory.used',
                providerId: 'generator',
                scope: 'LOAD_GENERATOR',
                scopeLabel: 'Load generator',
                seriesLabel: 'Load generator process memory',
                unitSymbol: 'bytes',
                points: [{ atIso: '2026-08-22T09:00:00Z', value: 300_000_000 }],
                display: '286.1 MB',
                limitDisplay: '16.8 GB',
                utilisationDisplay: '2%',
                atItsLimit: false,
                utilisationFraction: 0.02,
                limitValue: null,
              },
              {
                signalId: 'metric:generator.host.memory.used',
                providerId: 'generator',
                scope: 'LOAD_GENERATOR_HOST',
                scopeLabel: 'Load generator host',
                seriesLabel: 'Load generator host memory',
                unitSymbol: 'bytes',
                points: [{ atIso: '2026-08-22T09:00:00Z', value: 15_900_000_000 }],
                display: '14.8 GB',
                limitDisplay: '16.8 GB',
                utilisationDisplay: '95%',
                atItsLimit: false,
                utilisationFraction: 0.95,
                limitValue: null,
              },
            ],
          },
        ],
      },
    });

    expect(screen.getByText('Memory — system under test')).toBeInTheDocument();
    // The generator's own process/container and its host answer "can this run's evidence be
    // trusted", not "how did the system under test behave" — that question already has its own
    // place (the collapsed "Load generator" disclosure), not a second chart competing with the
    // system under test's own for the same attention on the main path.
    expect(screen.queryByText('Memory — load generator')).not.toBeInTheDocument();
    expect(screen.queryByText('Memory — load generator host')).not.toBeInTheDocument();
  });

  it('separates the resources table into service, generator process/container, and generator host groups', () => {
    const evidence = baseEvidence();
    const signal = (overrides: Partial<RunEvidence['resources']['service'][number]>) => ({
      id: 'metric:x',
      name: 'x',
      kind: 'MEMORY',
      kindLabel: 'Memory',
      scope: 'SYSTEM_UNDER_TEST',
      scopeLabel: 'System under test',
      display: '1 MB',
      limitDisplay: '',
      utilisationDisplay: '',
      atItsLimit: false,
      describe: 'x',
      utilisationFraction: null,
      ...overrides,
    });
    render({
      ...evidence,
      resources: {
        present: true,
        service: [signal({ id: 'svc', name: 'Container memory' })],
        generator: [signal({ id: 'gen', name: 'Load generator process memory', atItsLimit: true })],
        generatorHost: [
          signal({
            id: 'gen-host',
            name: 'Load generator host memory',
            atItsLimit: true,
            utilisationDisplay: '95%',
          }),
        ],
        generatorObserved: true,
        gaps: [],
      },
    });

    expect(screen.getByText('Container memory')).toBeInTheDocument();
    expect(screen.getByText('Load generator process memory')).toBeInTheDocument();
    expect(screen.getByText('Load generator host memory')).toBeInTheDocument();
    // A generator process/container at its limit withholds capacity — a stronger warning than a
    // shared host under pressure, which only qualifies confidence. Both must render distinctly.
    expect(screen.getByText('Load generator pressure')).toBeInTheDocument();
    expect(screen.getByText('Load generator host pressure')).toBeInTheDocument();
  });

  it('a partial series is captioned as partial rather than rendered as though it were complete', () => {
    const evidence = baseEvidence();
    render({
      ...evidence,
      timeline: { ...evidence.timeline, present: true },
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
                utilisationFraction: null,
                limitValue: null,
              },
            ],
          },
        ],
      },
    });

    expect(screen.getByText(/Resource series are partial/)).toBeInTheDocument();
    expect(screen.getByText(/the artifact could not be written to/)).toBeInTheDocument();
  });
});
