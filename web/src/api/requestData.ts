// Field-for-field against com.acltabontabon.vortex.app.web.RequestDataApiController / RequestDataDtos.
//
// Two shapes, deliberately: a ValueSlot is what the server tells us about a value, and a
// ValueUpdate is what we tell it back. Three of a slot's fields — `required`, `environmentSet`,
// `suggestion` — are things only the server knows, and sending them back would suggest we could
// change them.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from './client';

/** Where a value is carried. The same four positions the domain models. */
export type ValueTarget = 'HEADER' | 'PATH' | 'QUERY' | 'BODY_FIELD';

/** Which source a value has. Empty string means "not configured", which is not the same as fixed. */
export type ValueSource = '' | 'fixed' | 'generated' | 'dataset' | 'environment';

export interface Suggestion {
  source: ValueSource;
  generator: string | null;
  choices: string[];
  /** In the specification's own terms, so a person can judge it rather than trust it. */
  reason: string;
}

export interface ValueSlot {
  target: ValueTarget;
  name: string;
  required: boolean;
  source: ValueSource;
  literal: string | null;
  generator: string | null;
  lifecycle: string | null;
  minimum: number | null;
  maximum: number | null;
  length: number | null;
  dataset: string | null;
  datasetScope: string | null;
  field: string | null;
  environmentVariable: string | null;
  /** Whether the variable exists on this machine right now — never its value. */
  environmentSet: boolean;
  suggestion: Suggestion | null;
}

export interface ValueUpdate {
  target: ValueTarget;
  name: string;
  source: ValueSource;
  literal?: string | null;
  generator?: string | null;
  lifecycle?: string | null;
  minimum?: number | null;
  maximum?: number | null;
  length?: number | null;
  dataset?: string | null;
  datasetScope?: string | null;
  field?: string | null;
  environmentVariable?: string | null;
}

export interface DatasetSummary {
  name: string;
  scope: 'local' | 'portable';
  format: string;
  records: number;
  fields: string[];
  location: string;
  /** The first few records only. A dataset worth having is too big to render. */
  preview: Record<string, unknown>[];
  /** The exact file making this portable would write, so we can say so before it happens. */
  promotionTarget: string;
  /** Why this dataset cannot currently be read, or empty. */
  problem: string;
}

export interface GeneratorInfo {
  key: string;
  label: string;
  meaning: string;
  usesRange: boolean;
  usesLength: boolean;
}

export interface RequestDataView {
  operationId: string;
  label: string;
  method: string;
  path: string;
  mutating: boolean;
  reviewed: boolean;
  body: string;
  values: ValueSlot[];
  datasets: DatasetSummary[];
  generators: GeneratorInfo[];
}

const REQUEST_DATA_KEY = (serviceId: string, operationId: string) =>
  ['service', serviceId, 'request-data', operationId] as const;

const DATASETS_KEY = (serviceId: string) => ['service', serviceId, 'datasets'] as const;

export function useRequestDataQuery(serviceId: string, operationId: string | null) {
  return useQuery({
    queryKey: REQUEST_DATA_KEY(serviceId, operationId ?? ''),
    queryFn: () =>
      apiClient.get<RequestDataView>(
        `/api/services/${serviceId}/operations/${encodeURIComponent(operationId!)}/request-data`
      ),
    enabled: operationId !== null,
  });
}

export function useSaveRequestDataMutation(serviceId: string, operationId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: { body: string; values: ValueUpdate[] }) =>
      apiClient.post<{ message: string }>(
        `/api/services/${serviceId}/operations/${encodeURIComponent(operationId)}/request-data`,
        request
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: REQUEST_DATA_KEY(serviceId, operationId),
      });
    },
  });
}

export function useDatasetsQuery(serviceId: string) {
  return useQuery({
    queryKey: DATASETS_KEY(serviceId),
    queryFn: () => apiClient.get<DatasetSummary[]>(`/api/services/${serviceId}/datasets`),
  });
}

export function useUploadDatasetMutation(serviceId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: { name: string; format: string; scope: string; content: string }) =>
      apiClient.post<DatasetSummary>(`/api/services/${serviceId}/datasets`, request),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: DATASETS_KEY(serviceId) }),
  });
}

export function usePromoteDatasetMutation(serviceId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (name: string) =>
      apiClient.post<DatasetSummary>(
        `/api/services/${serviceId}/datasets/${encodeURIComponent(name)}/promote`
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: DATASETS_KEY(serviceId) }),
  });
}

export function useDeleteDatasetMutation(serviceId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ name, scope }: { name: string; scope: string }) =>
      apiClient.delete<{ message: string }>(
        `/api/services/${serviceId}/datasets/${encodeURIComponent(name)}?scope=${scope}`
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: DATASETS_KEY(serviceId) }),
  });
}

/** How a target is named where a person reads it. */
export const TARGET_LABEL: Record<ValueTarget, string> = {
  HEADER: 'Header',
  PATH: 'Path',
  QUERY: 'Query',
  BODY_FIELD: 'Body',
};

/**
 * A slot, as an update.
 *
 * <p>Carries only the fields the chosen source reads. Sending a dataset name alongside a fixed
 * literal would be harmless and confusing, and the server would ignore it — which is exactly the
 * kind of thing that later reads as a bug.
 */
export function toUpdate(slot: ValueSlot): ValueUpdate {
  const base = { target: slot.target, name: slot.name, source: slot.source };
  switch (slot.source) {
    case 'fixed':
      return { ...base, literal: slot.literal ?? '' };
    case 'generated':
      return {
        ...base,
        generator: slot.generator,
        lifecycle: slot.lifecycle,
        minimum: slot.minimum,
        maximum: slot.maximum,
        length: slot.length,
      };
    case 'dataset':
      return {
        ...base,
        dataset: slot.dataset,
        datasetScope: slot.datasetScope ?? 'local',
        field: slot.field,
      };
    case 'environment':
      return { ...base, environmentVariable: slot.environmentVariable };
    default:
      return base;
  }
}
