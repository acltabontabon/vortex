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

/** The same four capability questions the onboarding tiles ask, scoped to one specific service. */
export function commandHref(
  service: ServiceCard,
  action: 'find-limit' | 'validate-capacity' | 'compare-runs' | 'production-traffic',
): string {
  switch (action) {
    case 'find-limit':
    case 'validate-capacity':
      return service.canRun ? `/services/${service.id}` : `/services/${service.id}/configuration`;
    case 'compare-runs':
      return `/services/${service.id}/evidence`;
    case 'production-traffic':
      return `/services/${service.id}`;
  }
}
