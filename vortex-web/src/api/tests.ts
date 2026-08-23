// Field-for-field against com.acltabontabon.vortex.app.web.TestsApiController.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from './client';

export interface CatalogOperation {
  id: string;
  label: string;
  method: string;
  path: string;
  mutating: boolean;
}

export function useCatalogOperationsQuery(serviceId: string) {
  return useQuery({
    queryKey: ['service', serviceId, 'catalog', 'operations'],
    queryFn: () => apiClient.get<CatalogOperation[]>(`/api/services/${serviceId}/catalog/operations`),
  });
}

export interface TestEdit {
  name: string;
  description: string;
  objective: string;
  type: string;
  model: 'OPEN' | 'CLOSED';
  rate: number | null;
  vus: number | null;
  durationMinutes: number;
  ramping: boolean;
  peakRate: number | null;
  stages: number | null;
  singleOperation: string | null;
  weights: Record<string, number>;
}

export function useTestEditQuery(serviceId: string, name: string | undefined) {
  return useQuery({
    queryKey: ['service', serviceId, 'tests', name],
    queryFn: () => apiClient.get<TestEdit>(`/api/services/${serviceId}/tests/${name}`),
    enabled: name !== undefined,
  });
}

export interface TestSaveRequest {
  name: string;
  originalName?: string;
  type: string;
  description?: string;
  objective?: string;
  model: 'OPEN' | 'CLOSED';
  rate?: number;
  vus?: number;
  durationMinutes?: number;
  peakRate?: number;
  stages?: number;
  singleOperation?: string;
  weights?: Record<string, number>;
}

export interface TestSaveResponse {
  name: string;
}

function invalidateTests(queryClient: ReturnType<typeof useQueryClient>, serviceId: string) {
  queryClient.invalidateQueries({ queryKey: ['service', serviceId, 'tests'] });
  queryClient.invalidateQueries({ queryKey: ['service', serviceId, 'overview'] });
}

export function useSaveTestMutation(serviceId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: TestSaveRequest) =>
      apiClient.post<TestSaveResponse>(`/api/services/${serviceId}/tests`, request),
    onSuccess: () => invalidateTests(queryClient, serviceId),
  });
}

export function useDuplicateTestMutation(serviceId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (name: string) =>
      apiClient.post<TestSaveResponse>(`/api/services/${serviceId}/tests/${name}/duplicate`),
    onSuccess: () => invalidateTests(queryClient, serviceId),
  });
}

export function useDeleteTestMutation(serviceId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (name: string) =>
      apiClient.post<void>(`/api/services/${serviceId}/tests/${name}/delete`),
    onSuccess: () => invalidateTests(queryClient, serviceId),
  });
}

export interface PreviewRequest {
  model: 'OPEN' | 'CLOSED';
  rate?: number;
  vus?: number;
  durationMinutes?: number;
  peakRate?: number;
  stages?: number;
  singleOperation?: string;
  weights?: Record<string, number>;
}

export interface StageDto {
  levelValue: number;
  levelDisplay: string;
  durationMillis: number;
  durationDisplay: string;
}

/** The load shape's real quantities — a level and a duration per stage, in the one unit
 *  (`requests/sec` or `VUs`) every stage in this response shares. Never pixel geometry: the
 *  browser turns these into a chart's coordinates itself (see `LoadShapeChart.tsx`), which is
 *  layout, not business arithmetic — `TestDefinitions.shape()` is still the only place stage
 *  levels/durations are decided. */
export interface ShapeDto {
  unit: string;
  ramping: boolean;
  peakLevelValue: number;
  peakLevelDisplay: string;
  totalDurationMillis: number;
  stages: StageDto[];
}

export interface PreviewResponse {
  composition: import('./workspace').MixRow[] | null;
  shape: ShapeDto | null;
  problem: string | null;
}

export function usePreviewMutation(serviceId: string) {
  return useMutation({
    mutationFn: (request: PreviewRequest) =>
      apiClient.post<PreviewResponse>(`/api/services/${serviceId}/tests/preview`, request),
  });
}

export interface ApplyProductionResponse {
  applied: boolean;
  message: string;
  createdNames: string[];
}

export function useApplyProductionMutation(serviceId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      apiClient.post<ApplyProductionResponse>(`/api/services/${serviceId}/production/apply`),
    onSuccess: () => invalidateTests(queryClient, serviceId),
  });
}
