// Field-for-field against com.acltabontabon.vortex.app.web.HomeApiController's DTO records.

import { useQuery } from '@tanstack/react-query';
import { apiClient } from './client';

export type Verdict = 'PASS' | 'FAIL' | 'NOT_EVALUATED';

export interface RunRef {
  id: string;
  testTypeLabel: string;
  stateLabel: string;
}

export interface VerdictInfo {
  verdict: Verdict;
  verdictLabel: string;
  testTypeLabel: string;
  answer: string;
  runId: string;
  relativeTime: string;
  isoTimestamp: string;
  p95: string | null;
}

export type MarkerKind = 'PRODUCTION' | 'TESTED_CAPACITY' | 'FIRST_FAILING';

export interface RangeMarker {
  kind: MarkerKind;
  label: string;
  displayWithUnit: string;
}

/** One configured workload — enough of it to resolve an intent against, and no more. Per-test
 *  runnability is deliberately absent: it costs a subprocess per workload server-side, and it is
 *  preflight's question anyway. */
export interface WorkloadRef {
  name: string;
  /** The `TestType` enum name, not its label — behaviour keys off this, prose never. */
  testType: string;
  testTypeLabel: string;
  productionInformed: boolean;
}

export interface ServiceCard {
  id: string;
  name: string;
  description: string | null;
  running: boolean;
  runningRun: RunRef | null;
  canRun: boolean;
  blockers: string[];
  nextStepText: string | null;
  /** The FIRST workload's label — a weaker claim than `workloads`. Feeds `workloadSummary()`. */
  workloadTestTypeLabel: string | null;
  workloadProductionInformed: boolean | null;
  workloads: WorkloadRef[];
  apiImported: boolean;
  /** Never a blocker — a run without objectives still measures everything, it just decides nothing. */
  objectivesConfigured: boolean;
  productionObserved: boolean;
  /** Finished runs within the homepage's own recent scan — a lower bound, not total history. */
  recentTerminalRunCount: number;
  updatedAtRelative: string;
  updatedAtIso: string;
  headroomDisplay: string | null;
  latestVerdict: VerdictInfo | null;
  rangeMarkers: RangeMarker[];
  satisfiedCount: number;
  totalCount: number;
  evidencePredatesRelease: boolean;
  releaseGapText: string | null;
}

export interface HomeResponse {
  cards: ServiceCard[];
}

export function useHomeQuery() {
  return useQuery({
    queryKey: ['home'],
    queryFn: () => apiClient.get<HomeResponse>('/api/home'),
  });
}
