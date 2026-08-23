// Field-for-field against dev.vortex.app.web.HomeApiController's DTO records.

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

export interface ServiceCard {
  id: string;
  name: string;
  description: string | null;
  running: boolean;
  runningRun: RunRef | null;
  canRun: boolean;
  blockers: string[];
  nextStepText: string | null;
  workloadTestTypeLabel: string | null;
  workloadProductionInformed: boolean | null;
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
