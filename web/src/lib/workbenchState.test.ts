import { describe, expect, it } from 'vitest';
import type { ServiceCard, WorkloadRef } from '../api/home';
import { resolveCommand, type CommandAction } from './workbenchState';

const ALL_ACTIONS: CommandAction[] = [
  'find-limit',
  'validate-capacity',
  'compare-runs',
  'production-traffic',
];

function workload(name: string, testType: string, productionInformed = false): WorkloadRef {
  return { name, testType, testTypeLabel: testType, productionInformed };
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
    workloads: [],
    apiImported: true,
    objectivesConfigured: true,
    productionObserved: false,
    recentTerminalRunCount: 0,
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

/** Assert on a parsed URL, never on the raw string — a passing substring match proves nothing. */
function parse(href: string | null): URL {
  expect(href).not.toBeNull();
  return new URL(href!, 'http://vortex.test');
}

/**
 * The strip's whole purpose is that pressing a command does the thing the command says. These
 * assert the resolution, not the prose: which workload it names, which destination it reaches, and
 * that an unavailable command cannot become a link that leads nowhere.
 */
describe('resolveCommand', () => {
  describe('run-shaped intents resolve to a workload of their own type', () => {
    // Position must not matter: `workloads[0]` is precisely what this replaced.
    it.each([
      ['listed first', [workload('breakpoint-ramp', 'BREAKPOINT'), workload('steady', 'AVERAGE_LOAD')]],
      ['listed second', [workload('steady', 'AVERAGE_LOAD'), workload('breakpoint-ramp', 'BREAKPOINT')]],
    ])('find-limit runs the breakpoint workload when it is %s', (_position, workloads) => {
      const url = parse(resolveCommand(card({ workloads }), 'find-limit').href);

      expect(url.pathname).toBe('/services/checkout/run');
      expect(url.searchParams.get('workload')).toBe('breakpoint-ramp');
    });

    it('validate-capacity runs the average-load workload, not whichever is first', () => {
      const workloads = [workload('breakpoint-ramp', 'BREAKPOINT'), workload('steady', 'AVERAGE_LOAD')];

      const url = parse(resolveCommand(card({ workloads }), 'validate-capacity').href);

      expect(url.searchParams.get('workload')).toBe('steady');
    });

    it('names a workload exactly, whatever characters it contains', () => {
      const workloads = [workload('orders & checkout/v2', 'BREAKPOINT')];

      const url = parse(resolveCommand(card({ workloads }), 'find-limit').href);

      expect(url.searchParams.get('workload')).toBe('orders & checkout/v2');
    });

    it.each([
      ['find-limit', 'BREAKPOINT'],
      ['validate-capacity', 'AVERAGE_LOAD'],
    ] as const)('%s opens a composer seeded with %s when no such workload exists', (action, testType) => {
      const url = parse(resolveCommand(card({ workloads: [] }), action).href);

      expect(url.pathname).toBe('/services/checkout');
      expect(url.searchParams.get('compose')).toBe('new');
      expect(url.searchParams.get('type')).toBe(testType);
    });

    it('cannot offer a composer with no operations to compose a mix from', () => {
      const resolution = resolveCommand(card({ workloads: [], apiImported: false }), 'find-limit');

      expect(resolution.enabled).toBe(false);
      expect(resolution.unavailableReason).toMatch(/OpenAPI/);
    });
  });

  // The regression this change exists for: three of the four used to collapse onto /services/{id}.
  it('resolves the four intents to four distinct destinations', () => {
    const service = card({
      workloads: [workload('breakpoint-ramp', 'BREAKPOINT'), workload('steady', 'AVERAGE_LOAD')],
    });

    const destinations = ALL_ACTIONS.map((action) => resolveCommand(service, action).href);

    expect(new Set(destinations).size).toBe(ALL_ACTIONS.length);
  });

  // Structural, so no future branch can render a control that goes nowhere.
  it('never leaves a disabled command holding an href, or an enabled one without a reason to exist', () => {
    const fixtures = [
      card(),
      card({ apiImported: false }),
      card({ workloads: [workload('steady', 'AVERAGE_LOAD')] }),
      card({ objectivesConfigured: false }),
      card({ productionObserved: true, recentTerminalRunCount: 12 }),
    ];

    for (const service of fixtures) {
      for (const action of ALL_ACTIONS) {
        const resolution = resolveCommand(service, action);

        expect(resolution.detail).not.toBe('');
        if (resolution.enabled) {
          expect(resolution.href).not.toBeNull();
          expect(resolution.unavailableReason).toBeNull();
        } else {
          expect(resolution.href).toBeNull();
          expect(resolution.unavailableReason).not.toBeNull();
        }
      }
    }
  });

  describe('objectives qualify a run, they never block it', () => {
    // ProjectReadiness classifies OBJECTIVES as EVALUATION, not REQUIRED, on purpose: a run without
    // them still takes every measurement, it just reaches no verdict. Disabling here would be the
    // interface overruling that decision.
    it.each(['find-limit', 'validate-capacity'] as const)('%s stays enabled without objectives', (action) => {
      const service = card({
        workloads: [workload('breakpoint-ramp', 'BREAKPOINT'), workload('steady', 'AVERAGE_LOAD')],
        objectivesConfigured: false,
      });

      const resolution = resolveCommand(service, action);

      expect(resolution.enabled).toBe(true);
      expect(resolution.detail).toMatch(/no SLO limit|no verdict/);
    });
  });

  describe('compare-runs', () => {
    it('reaches the run list filtered to this service', () => {
      const url = parse(resolveCommand(card({ recentTerminalRunCount: 4 }), 'compare-runs').href);

      expect(url.pathname).toBe('/runs');
      expect(url.searchParams.get('project')).toBe('checkout');
    });

    // The count is bounded by the homepage's own recent scan, so a service with a long older
    // history can report 0. Disabling on that would state something false.
    it.each([0, 1, 2, 40])('stays enabled at %i recent runs', (recentTerminalRunCount) => {
      expect(resolveCommand(card({ recentTerminalRunCount }), 'compare-runs').enabled).toBe(true);
    });

    it('never claims a count without saying it is only what was recently scanned', () => {
      for (const recentTerminalRunCount of [0, 1, 2, 40]) {
        const { detail } = resolveCommand(card({ recentTerminalRunCount }), 'compare-runs');

        if (/\d/.test(detail)) expect(detail).toMatch(/recent/);
      }
    });
  });

  describe('production-traffic', () => {
    it('reaches the production section of configuration', () => {
      const url = parse(resolveCommand(card(), 'production-traffic').href);

      expect(url.pathname).toBe('/services/checkout/configuration');
      expect(url.hash).toBe('#production');
    });

    it.each([
      ['nothing observed yet', card({ productionObserved: false }), /Record/],
      ['observed but unused', card({ productionObserved: true, workloads: [workload('steady', 'AVERAGE_LOAD')] }), /Ground/],
      ['already grounding a workload', card({ productionObserved: true, workloads: [workload('steady', 'AVERAGE_LOAD', true)] }), /Review/],
    ])('says what is left to do when %s', (_state, service, expected) => {
      expect(resolveCommand(service, 'production-traffic').detail).toMatch(expected);
    });
  });
});
