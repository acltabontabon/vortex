// Field-for-field against dev.vortex.app.web.ConfigurationApiController / ConfigurationDtos.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from './client';
import { invalidateService } from './workspace';
import type { MixRow, Production } from './workspace';

export interface EnvironmentOption {
  name: string;
  label: string;
  description: string;
}

export interface Environment {
  name: string;
  baseUrl: string;
  type: string;
  typeLabel: string;
  dependencyMode: string;
  dependencyModeLabel: string;
  classification: string;
  classificationLabel: string;
  classificationCaveat: string;
  hasSecretReferences: boolean;
  maskedHeaders: Record<string, string>;
}

export interface LabStatus {
  usable: boolean;
  dockerAvailable: boolean;
  daemonRunning: boolean;
  composeAvailable: boolean;
  version: string;
  remedy: string;
}

export interface LabActivity {
  operationLabel: string;
  operationCommand: string;
  composeFileDisplay: string;
  succeeded: boolean;
  failed: boolean;
  resultMessage: string | null;
  output: string[];
}

export interface LocalLab {
  configured: boolean;
  composeFileDisplay: string | null;
  status: LabStatus;
  running: boolean;
  activity: LabActivity | null;
}

export interface WorkloadSuggestion {
  name: string;
  rateDisplay: string;
  derivation: string;
}

export interface ObservationSource {
  kind: string;
  endpoint: string;
  serviceIdentifier: string;
  windowDisplay: string;
  maskedHeaders: Record<string, string>;
}

export interface ThresholdEdit {
  p95Millis: number | null;
  p99Millis: number | null;
  errorPercent: number | null;
  describe: string[];
}

export interface ConfigurationFile {
  yaml: string;
  path: string | null;
}

export interface CatalogOperation {
  id: string;
  method: string;
  path: string;
  summary: string;
  primaryTag: string;
  kind: 'READ' | 'MUTATION';
  requiresReview: boolean;
  reviewed: boolean;
}

export interface Catalog {
  imported: boolean;
  title: string | null;
  sourceRef: string | null;
  operationCount: number;
  mutatingCount: number;
  operations: CatalogOperation[];
}

export interface Configuration {
  serviceVersion: string | null;
  environments: Environment[];
  environmentTypes: EnvironmentOption[];
  dependencyModes: EnvironmentOption[];
  localLab: LocalLab;
  production: Production | null;
  calibrationSuggestions: WorkloadSuggestion[];
  observationSource: ObservationSource | null;
  thresholds: ThresholdEdit;
  catalog: Catalog;
  file: ConfigurationFile;
}

const CONFIG_KEY = (id: string) => ['service', id, 'configuration'];

/*
 * Every mutation here invalidates the whole service, not just this page's own query. Configuration
 * is what readiness is computed from: adding an environment or importing an API changes the header's
 * readiness pill, Overview's facts and whether each test can run, and invalidating only
 * `CONFIG_KEY` left all of those showing the state from before the save until something else
 * happened to refetch them. `invalidateService` matches on the `['service', id]` prefix, so it
 * covers this page's query too.
 */


/**
 * Polls itself while the local lab has a command in flight — the same "poll only while running"
 * contract `lab-panel.html`'s `hx-trigger="every 2s"` enforced — and otherwise fetches once, like
 * every other page. One query for the whole page rather than a lab-specific one: a full round trip
 * is cheap for a local, single-user tool, and it is the only way every section's cross-dependencies
 * (a new environment changing readiness, a fetched observation changing calibration suggestions)
 * stay consistent with each other.
 */
export function useConfigurationQuery(id: string) {
  return useQuery({
    queryKey: CONFIG_KEY(id),
    queryFn: () => apiClient.get<Configuration>(`/api/services/${id}/configuration`),
    refetchInterval: (query) => (query.state.data?.localLab.running ? 2000 : false),
  });
}

function useConfigMutation<TRequest>(id: string, path: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: TRequest) =>
      apiClient.post<{ message: string }>(`/api/services/${id}${path}`, request),
    onSuccess: () => invalidateService(queryClient, id),
  });
}

export interface EnvironmentRequest {
  name: string;
  baseUrl: string;
  type: string;
  dependencies: string;
  productionLike?: boolean;
  headerNames?: string;
  headerValues?: string;
}

export const useAddEnvironmentMutation = (id: string) =>
  useConfigMutation<EnvironmentRequest>(id, '/environments');

export const useSetReleaseMutation = (id: string) =>
  useConfigMutation<{ serviceVersion: string }>(id, '/release');

export const useSetThresholdsMutation = (id: string) =>
  useConfigMutation<{ p95Millis?: number; p99Millis?: number; errorPercent?: number }>(
    id,
    '/thresholds'
  );

export interface ProductionRequest {
  averageRate?: number;
  p95ObservedRate?: number;
  peakRate: number;
  mixOperation?: string[];
  mixWeight?: number[];
  source?: string;
  observedFrom?: string;
  observedTo?: string;
  note?: string;
}

export const useRecordProductionMutation = (id: string) =>
  useConfigMutation<ProductionRequest>(id, '/production');

export interface FetchProductionResponse {
  succeeded: boolean;
  error: string | null;
  preview: Production | null;
}

export function useFetchProductionMutation(id: string) {
  return useMutation({
    mutationFn: () =>
      apiClient.post<FetchProductionResponse>(`/api/services/${id}/production/fetch`),
  });
}

export interface ObservationSourceRequest {
  source: string;
  endpoint: string;
  serviceIdentifier: string;
  window: string;
  headerName?: string[];
  headerValue?: string[];
}

export const useSaveObservationSourceMutation = (id: string) =>
  useConfigMutation<ObservationSourceRequest>(id, '/observation');

export interface TestConnectionResponse {
  succeeded: boolean;
  message: string;
}

export function useTestObservationSourceMutation(id: string) {
  return useMutation({
    mutationFn: (request: ObservationSourceRequest) =>
      apiClient.post<TestConnectionResponse>(`/api/services/${id}/observation/test`, request),
  });
}

export interface ImportRequest {
  url?: string;
  content?: string;
}

export interface ImportResponse {
  succeeded: boolean;
  message: string | null;
  info: string | null;
  error: string | null;
  errorDetails: string[];
}

export function useImportCatalogMutation(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: ImportRequest) =>
      apiClient.post<ImportResponse>(`/api/services/${id}/import`, request),
    onSuccess: () => invalidateService(queryClient, id),
  });
}

export function useReviewOperationMutation(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (operationId: string) =>
      apiClient.post<{ message: string }>(
        `/api/services/${id}/operations/${operationId}/review`
      ),
    onSuccess: () => invalidateService(queryClient, id),
  });
}

export const useSetComposeFileMutation = (id: string) =>
  useConfigMutation<{ composeFile: string }>(id, '/lab');

export function useClearComposeFileMutation(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => apiClient.post<{ message: string }>(`/api/services/${id}/lab/clear`),
    onSuccess: () => invalidateService(queryClient, id),
  });
}

function useLabAction(id: string, path: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => apiClient.post<{ message: string }>(`/api/services/${id}${path}`),
    onSuccess: () => invalidateService(queryClient, id),
  });
}

export const useLabUpMutation = (id: string) => useLabAction(id, '/lab/up');
export const useLabDownMutation = (id: string) => useLabAction(id, '/lab/down');
export const useLabDismissMutation = (id: string) => useLabAction(id, '/lab/dismiss');

export type { MixRow };
