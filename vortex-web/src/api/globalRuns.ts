// Field-for-field against dev.vortex.app.web.GlobalRunDtos and GlobalRunsApiController.

import { useQuery } from '@tanstack/react-query';
import { apiClient } from './client';
import { useAsyncPanel } from './asyncPanel';
import type { AiAvailability } from '../components/AsyncPanel';
import type { Analysis, MetricDelta } from './run';
import type { Verdict } from './workspace';

export interface ProjectOption {
  id: string;
  name: string;
}

export interface RunHistoryRow {
  executionId: string;
  projectId: string;
  projectName: string;
  serviceVersion: string | null;
  testTypeLabel: string;
  workloadName: string;
  environmentName: string;
  classificationLabel: string;
  terminal: boolean;
  verdict: Verdict;
  verdictLabel: string;
  stateLabel: string;
  offeredLoadDisplay: string;
  achievedRateDisplay: string | null;
  p95Display: string | null;
  relativeTime: string;
}

export interface RunHistory {
  rows: RunHistoryRow[];
  totalBeforeFilters: number;
  projects: ProjectOption[];
  evaluations: string[];
  workloadNames: string[];
  environments: string[];
  results: string[];
}

export interface RunHistoryFilters {
  project?: string | null;
  evaluation?: string | null;
  workload?: string | null;
  environment?: string | null;
  result?: string | null;
}

export function useRunHistoryQuery(filters: RunHistoryFilters) {
  const params = new URLSearchParams();
  if (filters.project) params.set('project', filters.project);
  if (filters.evaluation) params.set('evaluation', filters.evaluation);
  if (filters.workload) params.set('workload', filters.workload);
  if (filters.environment) params.set('environment', filters.environment);
  if (filters.result) params.set('result', filters.result);
  const query = params.toString();

  return useQuery({
    queryKey: ['runs', filters],
    queryFn: () => apiClient.get<RunHistory>(`/api/runs${query ? `?${query}` : ''}`),
  });
}

// ---------------------------------------------------------------- compare

export interface CompareSide {
  executionId: string;
  workloadName: string;
  serviceVersion: string | null;
  environmentName: string;
  requestedAtDisplay: string;
}

export interface CompareResult {
  baseline: CompareSide;
  candidate: CompareSide;
  baselineReleaseMissing: boolean;
  candidateReleaseMissing: boolean;
  supportsRegressionVerdict: boolean;
  notComparableExplanation: string;
  differences: string[];
  deltas: MetricDelta[];
  verdictLabel: string | null;
  verdictDescription: string | null;
}

export function useCompareQuery(baseline: string, candidate: string) {
  return useQuery({
    queryKey: ['runs', 'compare', baseline, candidate],
    queryFn: () =>
      apiClient.get<CompareResult>(
        `/api/runs/compare?baseline=${encodeURIComponent(baseline)}&candidate=${encodeURIComponent(candidate)}`
      ),
    enabled: !!baseline && !!candidate,
  });
}

export interface ComparisonAnalysisPanel {
  analysing: boolean;
  latest: Analysis | null;
  availability: AiAvailability;
}

export function useComparisonAnalysisPanel(baseline: string, candidate: string) {
  const suffix = `baseline=${encodeURIComponent(baseline)}&candidate=${encodeURIComponent(candidate)}`;
  return useAsyncPanel<ComparisonAnalysisPanel>({
    queryKey: ['runs', 'compare', baseline, candidate, 'analysis'],
    statusPath: `/api/runs/compare/analysis?${suffix}`,
    startPath: `/api/runs/compare/analyze?${suffix}`,
    isRunning: (status) => status.analysing,
  });
}
