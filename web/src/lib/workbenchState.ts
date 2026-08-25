import type { ServiceCard } from '../api/home';

export type WorkbenchState = 'setup' | 'ready' | 'running' | 'pass' | 'fail' | 'unevaluated';

export const STATUS: Record<WorkbenchState, { label: string; color: string }> = {
  setup: { label: 'Setup incomplete', color: 'var(--mantine-color-neutral-6)' },
  ready: { label: 'Ready to test', color: 'var(--mantine-color-neutral-6)' },
  running: { label: 'Running', color: 'var(--mantine-color-live-6)' },
  pass: { label: 'Pass', color: 'var(--mantine-color-pass-6)' },
  fail: { label: 'Fail', color: 'var(--mantine-color-fail-6)' },
  unevaluated: { label: 'Not evaluated', color: 'var(--mantine-color-neutral-6)' },
};

export function stateOf(service: ServiceCard): WorkbenchState {
  if (service.running) return 'running';
  if (service.latestVerdict) {
    if (service.latestVerdict.verdict === 'PASS') return 'pass';
    if (service.latestVerdict.verdict === 'FAIL') return 'fail';
    return 'unevaluated';
  }
  return service.canRun ? 'ready' : 'setup';
}

export function primaryAction(service: ServiceCard, state: WorkbenchState): { label: string; href: string } {
  switch (state) {
    case 'setup':
      return { label: 'Continue setup', href: `/services/${service.id}/configuration` };
    case 'ready':
      return { label: 'Run first test', href: `/services/${service.id}` };
    case 'running':
      return { label: 'View live run', href: `/runs/${service.runningRun!.id}` };
    case 'fail':
      return { label: 'Investigate', href: `/runs/${service.latestVerdict!.runId}` };
    default:
      return { label: 'Open evidence', href: `/runs/${service.latestVerdict!.runId}` };
  }
}

export function workloadSummary(service: ServiceCard): string | null {
  if (!service.workloadTestTypeLabel) return null;
  const source = service.workloadProductionInformed ? 'Production-informed workload' : 'Manually entered workload';
  return `${service.workloadTestTypeLabel} test setup · ${source}`;
}

/** Active work always sorts first; otherwise the more recent of "last run" and "last touched" wins. */
export function recencyMillis(service: ServiceCard): number {
  if (service.running) return Number.POSITIVE_INFINITY;
  const candidates = [service.updatedAtIso, service.latestVerdict?.isoTimestamp]
    .filter((value): value is string => Boolean(value))
    .map((value) => new Date(value).getTime())
    .filter((value) => !Number.isNaN(value));
  return candidates.length ? Math.max(...candidates) : 0;
}

export type CommandAction = 'find-limit' | 'validate-capacity' | 'compare-runs' | 'production-traffic';

/**
 * What one command will actually do for one service.
 *
 * <p>`href` is null exactly when `enabled` is false, so no renderer can produce a link that leads
 * nowhere: the type makes the dead link unrepresentable rather than merely discouraged.
 */
export interface CommandResolution {
  href: string | null;
  /** What pressing this does for THIS service — "Run breakpoint-ramp", "Set one up". Always present. */
  detail: string;
  enabled: boolean;
  /** Why not, in place of the detail, when it is unavailable. Null when enabled. */
  unavailableReason: string | null;
}

/** Which `TestType` each run-shaped intent is asking for. The two names this file knows. */
const INTENT_TEST_TYPE: Record<'find-limit' | 'validate-capacity', string> = {
  'find-limit': 'BREAKPOINT',
  'validate-capacity': 'AVERAGE_LOAD',
};

/** The first workload of that *exact* type — never `workloads[0]`, which is the bug this replaces. */
function workloadOfType(service: ServiceCard, testType: string) {
  return service.workloads.find((workload) => workload.testType === testType) ?? null;
}

function preflightHref(serviceId: string, workloadName: string): string {
  // Only the workload. The server defaults `environment` to the first configured one and there is
  // no per-test environment picker anywhere, so naming one here would re-derive a default the
  // server already owns; `objective` is a free-text override, not an id, and is none of our business.
  const params = new URLSearchParams({ workload: workloadName });
  return `/services/${serviceId}/run?${params}`;
}

function composeHref(serviceId: string, testType: string): string {
  const params = new URLSearchParams({ compose: 'new', type: testType });
  return `/services/${serviceId}?${params}`;
}

/**
 * What a run-shaped intent will produce, qualified by what the service is missing.
 *
 * <p>Objectives are deliberately a qualification and not a block — see `ProjectReadiness.Kind`,
 * where OBJECTIVES is EVALUATION rather than REQUIRED: a run without them still takes every
 * measurement it otherwise would, it just reaches no verdict. Saying so is more use than a
 * disabled button.
 */
function runDetail(service: ServiceCard, workloadName: string, action: CommandAction): string {
  if (service.objectivesConfigured) return `Run ${workloadName}`;
  const consequence = action === 'find-limit' ? 'no SLO limit without objectives' : 'reaches no verdict';
  return `Run ${workloadName} · ${consequence}`;
}

/** How many runs this service has, said only as far as the homepage's scan can honestly see. */
function compareDetail(count: number): string {
  if (count === 0) return 'No recent runs';
  if (count === 1) return '1 recent run — needs a second';
  return `Pick two of ${count} recent runs`;
}

function productionDetail(service: ServiceCard): string {
  if (!service.productionObserved) return 'Record what it receives';
  return service.workloads.some((workload) => workload.productionInformed)
    ? 'Review what was observed'
    : 'Ground its tests in it';
}

/**
 * The four capability questions, resolved against what this specific service actually has.
 *
 * <p>Each intent lands where its own question gets answered: a run-shaped intent goes straight to
 * preflight for the workload that asks it, or to a composer seeded with that test type when no such
 * workload exists yet. Comparison goes to the run list filtered to this service — not to a specific
 * pair, because whether two runs are comparable is a judgement `ComparisonService` makes about a
 * given pair, and picking "the two most recent" here would assert that with no basis.
 */
export function resolveCommand(service: ServiceCard, action: CommandAction): CommandResolution {
  switch (action) {
    case 'find-limit':
    case 'validate-capacity': {
      const testType = INTENT_TEST_TYPE[action];
      const workload = workloadOfType(service, testType);
      if (workload) {
        return {
          href: preflightHref(service.id, workload.name),
          detail: runDetail(service, workload.name, action),
          enabled: true,
          unavailableReason: null,
        };
      }
      // The composer builds an operation mix out of the catalog, so with nothing imported it would
      // open onto a form it cannot complete. A service adopted from a committed vortex.yaml can
      // reach here: it has workloads (so canRun holds) while its API was never imported.
      if (!service.apiImported) {
        return {
          href: null,
          detail: 'Set one up',
          enabled: false,
          unavailableReason: 'Import an OpenAPI document first',
        };
      }
      return {
        href: composeHref(service.id, testType),
        detail: 'Set one up',
        enabled: true,
        unavailableReason: null,
      };
    }
    case 'compare-runs':
      // Never disabled below two runs: the count is bounded by the homepage's own recent scan, so
      // "needs a second run" could be flatly false for a service with a long, older history. The
      // run list says the truth about itself on arrival; a control that lies does not.
      return {
        href: `/runs?${new URLSearchParams({ project: service.id })}`,
        detail: compareDetail(service.recentTerminalRunCount),
        enabled: true,
        unavailableReason: null,
      };
    case 'production-traffic':
      return {
        href: `/services/${service.id}/configuration#production`,
        detail: productionDetail(service),
        enabled: true,
        unavailableReason: null,
      };
  }
}
