// Field-for-field against com.acltabontabon.vortex.app.web.ThresholdsApiController.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from './client';
import { invalidateService } from './workspace';

/** Mirrors `ThresholdsApiController.ThresholdDto`. `id`/`describe` are only ever present on a value
 *  the server returned — never supply them when building a request. */
export interface ThresholdDto {
  id?: string;
  kind: 'LATENCY' | 'ERROR_RATE';
  percentile?: number | null;
  maxMillis?: number | null;
  maxErrorPercent?: number | null;
  operationId?: string | null;
  describe?: string;
}

/** Mirrors `ThresholdsApiController.ThresholdProvenanceDto` — the evidence snapshot committed
 *  alongside a threshold value at save time. `source` is one of `ThresholdSource`'s six constants. */
export interface ThresholdProvenanceDto {
  source: string;
  sourceLabel?: string;
  detail?: string;
  windowFrom?: string | null;
  windowTo?: string | null;
  derivation?: string;
  evidenceQuality?: string;
  baselineExecutionId?: string;
}

/** `rawValue` is milliseconds for a latency metric, percent for an error-rate metric — never parse
 *  `displayValue`, it exists for display only. */
export interface ThresholdEvidenceDto {
  displayValue: string;
  rawValue: number;
  sourceLabel: string;
  window: string;
  evidenceQuality: string;
  stale: boolean;
  runQuality: string | null;
  executionId: string | null;
}

export interface ThresholdRecommendationOptionDto {
  label: string;
  source: string;
  sourceLabel: string;
  displayValue: string;
  rawValue: number;
  derivation: string;
  evidenceQuality: string;
}

export interface ThresholdRecommendationPanelDto {
  production: ThresholdEvidenceDto | null;
  baselines: ThresholdEvidenceDto[];
  recommendations: ThresholdRecommendationOptionDto[];
}

export interface WorkloadThresholdsDto {
  thresholds: ThresholdDto[];
  provenance: Record<string, ThresholdProvenanceDto>;
}

/** A workload's own saved threshold overrides, and the evidence behind each one. */
export function useWorkloadThresholdsQuery(serviceId: string, workload: string, enabled = true) {
  return useQuery({
    queryKey: ['service', serviceId, 'tests', workload, 'thresholds'],
    queryFn: () =>
      apiClient.get<WorkloadThresholdsDto>(`/api/services/${serviceId}/tests/${workload}/thresholds`),
    enabled,
  });
}

/** The service-level objectives every workload inherits unless it overrides them, and the evidence
 *  behind each one — the normal home for a service's thresholds. */
export function useProjectThresholdsQuery(serviceId: string) {
  return useQuery({
    queryKey: ['service', serviceId, 'thresholds'],
    queryFn: () => apiClient.get<WorkloadThresholdsDto>(`/api/services/${serviceId}/thresholds`),
  });
}

/**
 * Evidence-backed candidate values for one metric — the "Help me choose" panel's entire payload.
 * Side-effect-free; never invalidates anything, the same "preview endpoints never invalidate"
 * discipline `useTestPrometheusDefaultsMutation` already follows.
 */
export function useThresholdRecommendationQuery(
  serviceId: string,
  workload: string,
  metric: 'LATENCY' | 'ERROR_RATE',
  percentile: number | null,
  improvementPercent: number | null,
  enabled = true
) {
  const params = new URLSearchParams({ workload, metric });
  if (percentile !== null) params.set('percentile', String(percentile));
  if (improvementPercent !== null) params.set('improvementPercent', String(improvementPercent));
  return useQuery({
    queryKey: ['service', serviceId, 'tests', 'threshold-recommendation', workload, metric, percentile, improvementPercent],
    queryFn: () =>
      apiClient.get<ThresholdRecommendationPanelDto>(
        `/api/services/${serviceId}/tests/threshold-recommendation?${params.toString()}`
      ),
    enabled,
  });
}

export interface SanityFindingDto {
  severity: 'INFORMATION' | 'CAUTION' | 'INVALID';
  thresholdId: string;
  message: string;
}

export interface SanityCheckRequest {
  thresholds: ThresholdDto[];
  workload?: string;
  /** Reference value (ms for latency, percent for error rate) keyed by threshold id — per-threshold,
   *  since a p95 and a p99 objective compare against different production percentiles. */
  productionByThresholdId?: Record<string, number>;
  baselineByThresholdId?: Record<string, number>;
}

export interface SanityCheckResponse {
  findings: SanityFindingDto[];
  blocksSave: boolean;
  /** Keyed by threshold id — the live "52% stricter than current production behavior" text. */
  comparisons: Record<string, string>;
}

/** Live-typing feedback and, again, the server-side gate at save time. Never invalidates anything. */
export function useThresholdSanityCheckMutation(serviceId: string) {
  return useMutation({
    mutationFn: (request: SanityCheckRequest) =>
      apiClient.post<SanityCheckResponse>(`/api/services/${serviceId}/tests/thresholds/sanity-check`, request),
  });
}

export interface SaveThresholdsRequest {
  thresholds: ThresholdDto[];
  provenance?: Record<string, ThresholdProvenanceDto>;
}

export interface SaveThresholdsResponse {
  ok: boolean;
  error: string | null;
  findings: SanityFindingDto[];
}

export function useSaveThresholdsMutation(serviceId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ workload, request }: { workload: string; request: SaveThresholdsRequest }) =>
      apiClient.put<SaveThresholdsResponse>(
        `/api/services/${serviceId}/tests/${workload}/thresholds`,
        request
      ),
    onSuccess: (response) => {
      if (response.ok) invalidateService(queryClient, serviceId);
    },
  });
}

/** Saves the service-level objectives, and the evidence behind each one. */
export function useSaveProjectThresholdsMutation(serviceId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: SaveThresholdsRequest) =>
      apiClient.put<SaveThresholdsResponse>(`/api/services/${serviceId}/thresholds`, request),
    onSuccess: (response) => {
      if (response.ok) invalidateService(queryClient, serviceId);
    },
  });
}

export interface ThresholdHistoryEntryDto {
  executionId: string;
  value: string;
  at: string;
}

export function useThresholdHistoryQuery(serviceId: string, workload: string, thresholdId: string, enabled = true) {
  return useQuery({
    queryKey: ['service', serviceId, 'tests', workload, 'thresholds', 'history', thresholdId],
    queryFn: () =>
      apiClient.get<ThresholdHistoryEntryDto[]>(
        `/api/services/${serviceId}/tests/${workload}/thresholds/history?thresholdId=${encodeURIComponent(thresholdId)}`
      ),
    enabled,
  });
}

export interface NarrativeResponse {
  narrative: string;
  breakpointCondition: string | null;
}

/** The deterministic, templated summary of what a proposed set of objectives requires. */
export function useThresholdNarrativeMutation(serviceId: string) {
  return useMutation({
    mutationFn: (thresholds: ThresholdDto[]) =>
      apiClient.post<NarrativeResponse>(`/api/services/${serviceId}/tests/thresholds/narrative`, { thresholds }),
  });
}
