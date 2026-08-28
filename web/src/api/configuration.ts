// Field-for-field against com.acltabontabon.vortex.app.web.ConfigurationApiController / ConfigurationDtos.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from './client';
import { invalidateService } from './workspace';
import type { MixRow, Production } from './workspace';

export interface EnvironmentOption {
  name: string;
  label: string;
  description: string;
}

/** Mirrors `com.acltabontabon.vortex.app.web.ConfigurationDtos.ExecutionTargetSummaryDto`. */
export interface ExecutionTargetSummary {
  /** `EXTERNAL_ENDPOINT` | `DOCKER_IMAGE` | `DOCKER_COMPOSE`. */
  kind: string;
  summary: string;
  ownershipLabel: string;
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
  target: ExecutionTargetSummary;
  productionLike: boolean;
  // Target-detail fields — the read side of EnvironmentRequest's write-only shape, present only
  // for the target kind this environment actually has. Used to prefill an edit form.
  image: string | null;
  containerPort: number | null;
  cpuMillicores: number | null;
  memoryMebibytes: number | null;
  readinessPath: string | null;
  readinessExpectedStatus: number | null;
  readinessTimeoutSeconds: number | null;
  composeFile: string | null;
  composeService: string | null;
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
  transport: string;
  endpoint: string;
  serviceIdentifier: string;
  windowDisplay: string;
  maskedHeaders: Record<string, string>;
  /** Effective Prometheus label names — the override when set, otherwise the Micrometer default.
   *  Ignored for Dynatrace. */
  serviceLabel: string;
  routeLabel: string;
  methodLabel: string;
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
  name: string;
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
  // Target configuration — mirrors ConfigurationApiController.EnvironmentRequest. targetKind absent
  // or blank means EXTERNAL_ENDPOINT, matching the backend's own default and vortex.yaml's.
  targetKind?: string;
  image?: string;
  containerPort?: number;
  // Wire field is deliberately millicores, matching CpuAllocation's integer-millicore domain
  // representation — never a float "cpuCores" field. A form expressed in cores converts with
  // Math.round(cores * 1000) before building this request; see EnvironmentsSection.
  cpuMillicores?: number;
  memoryMebibytes?: number;
  readinessPath?: string;
  readinessExpectedStatus?: number;
  readinessTimeoutSeconds?: number;
  composeFile?: string;
  composeService?: string;
}

export const useAddEnvironmentMutation = (id: string) =>
  useConfigMutation<EnvironmentRequest>(id, '/environments');

/**
 * Written by hand rather than through {@link useConfigMutation}: that helper always POSTs, and
 * this is the one environment mutation that deletes.
 */
export function useDeleteEnvironmentMutation(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (name: string) =>
      apiClient.delete<{ message: string }>(
        `/api/services/${id}/environments/${encodeURIComponent(name)}`
      ),
    onSuccess: () => invalidateService(queryClient, id),
  });
}

export interface TargetValidationResponse {
  valid: boolean;
  checks: string[];
}

/**
 * "Test Connection" for a Docker/Compose target's configuration form — checks whatever the request
 * body describes, never what's already saved, and never starts anything (the backend calls only
 * `TargetExecutor#checkAvailability`, never `prepare`). Written by hand rather than through {@link
 * useConfigMutation}: that helper's `path` is fixed at call-construction time, but this endpoint's
 * path is parameterized on the environment name carried inside the request itself.
 */
export function useValidateTargetMutation(id: string) {
  return useMutation({
    mutationFn: (request: EnvironmentRequest) =>
      apiClient.post<TargetValidationResponse>(
        `/api/services/${id}/environments/${encodeURIComponent(request.name)}/target/validate`,
        request
      ),
  });
}

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

/** Distinct from `FetchProductionResponse`: `production` here is what was actually persisted. */
export interface FetchAndSaveProductionResponse {
  succeeded: boolean;
  error: string | null;
  production: Production | null;
}

/** Persists exactly what the most recent `/production/fetch` for this service already retrieved —
 *  no second live query against the observation source. Invalidates the service query on success so
 *  the rest of the page (Overview's "production observed" fact, calibration suggestions) picks it up
 *  too. */
export function useFetchAndSaveProductionMutation(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      apiClient.post<FetchAndSaveProductionResponse>(`/api/services/${id}/production/fetch-and-save`),
    onSuccess: (response) => {
      if (response.succeeded) {
        invalidateService(queryClient, id);
      }
    },
  });
}

export interface ObservationSourceRequest {
  source: string;
  transport?: string;
  endpoint: string;
  serviceIdentifier: string;
  window: string;
  headerName?: string[];
  headerValue?: string[];
  /** Prometheus label-name overrides. Blank/absent falls back to the Micrometer default
   *  (`application` / `uri` / `method`) individually, one label at a time. */
  serviceLabel?: string;
  routeLabel?: string;
  methodLabel?: string;
}

export const useSaveObservationSourceMutation = (id: string) =>
  useConfigMutation<ObservationSourceRequest>(id, '/observation');

/** `state` is one of `CONNECTED`, `CONNECTED_NO_DATA`, `AUTHENTICATION_FAILED`, `UNREACHABLE`,
 *  `INVALID_RESPONSE`, or `null` for a pre-flight refusal that never reached a connection at all. */
export interface TestConnectionResponse {
  succeeded: boolean;
  state: string | null;
  message: string;
}

export function useTestObservationSourceMutation(id: string) {
  return useMutation({
    mutationFn: (request: ObservationSourceRequest) =>
      apiClient.post<TestConnectionResponse>(`/api/services/${id}/observation/test`, request),
  });
}

export interface EntityCandidate {
  id: string;
  name: string;
}

export interface EntityLookupResponse {
  succeeded: boolean;
  candidates: EntityCandidate[];
  problem: string | null;
  remedy: string | null;
}

/** A best-effort search, not a save — never invalidates the service query. */
export function useLookupDynatraceEntityMutation(id: string) {
  return useMutation({
    mutationFn: (query: string) =>
      apiClient.post<EntityLookupResponse>(`/api/services/${id}/observation/dynatrace/entities`, { query }),
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
