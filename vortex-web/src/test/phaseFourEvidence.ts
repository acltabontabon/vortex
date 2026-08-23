import type { RunEvidence } from '../api/run';

/**
 * The Phase 4 sections of a run's evidence, at their empty-but-honest defaults.
 *
 * <p>Three test files each build a `RunEvidence` by hand, and each needed the same five new
 * sections. Spreading this into them keeps the additions in one place, so the next section added to
 * the contract is one edit rather than four — and so the defaults below stay consistent with what
 * the server actually sends for a run that measured none of it.
 *
 * <p>The defaults matter. A run whose engine reported no dropped work has an empty
 * `droppedDisplay`, not `'0'`; a run whose outcomes were never classified has `reported: false`,
 * not an empty table. Those distinctions are the point of the phase, and a fixture that flattened
 * them would let a component pass its tests while rendering an absence as a zero.
 */
export function phaseFourEvidence(): Pick<
  RunEvidence,
  'validity' | 'resources' | 'resourceTimeline' | 'capacity' | 'load' | 'reliability'
> {
  return {
    validity: {
      grade: 'VALID',
      label: 'Valid',
      explanation: 'The experiment was carried out as specified. Conclusions stand as measured.',
      assessed: true,
      permitsCapacityClaims: true,
      findings: [],
    },
    resources: {
      present: false,
      service: [],
      generator: [],
      generatorObserved: false,
      gaps: [],
    },
    resourceTimeline: {
      present: false,
      completenessStatus: 'UNAVAILABLE',
      completenessReason: '',
      plots: [],
    },
    capacity: {
      present: false,
      sustainableDisplay: '',
      refusal: 'No sustainable capacity was established by this run.',
      highestPassing: '',
      strengthLabel: 'Insufficient',
      conditions: [],
      limits: [],
      firstLimit: 'No limit was established.',
      noLimitEstablished: true,
      headroomDisplay: '',
      headroomRefusal: 'No production traffic has been recorded for this service.',
    },
    load: {
      requestedDisplay: '50 requests/sec',
      achievedDisplay: '49.8 requests/sec',
      iterationRateDisplay: '',
      // Empty, not '0': the engine reported nothing, which is not the same as nothing dropped.
      droppedDisplay: '',
      droppedWork: false,
      observedConcurrency: '',
      deliveredShare: '99%',
    },
    reliability: {
      // Nothing classified. Must never render as everything having succeeded.
      reported: false,
      errorRateDisplay: '0.08%',
      byResponseClass: [],
      byFailureClass: [],
    },
  };
}
