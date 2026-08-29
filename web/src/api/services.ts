// Field-for-field against com.acltabontabon.vortex.app.web.ServicesApiController.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from './client';
import type { ProposedEnvironment } from './discovery';

export interface ServiceListItem {
  id: string;
  name: string;
  description: string | null;
  serviceVersion: string | null;
}

export function useServicesListQuery() {
  return useQuery({
    queryKey: ['services'],
    queryFn: () => apiClient.get<ServiceListItem[]>('/api/services'),
  });
}

export interface CreateServiceRequest {
  name: string;
  description?: string;
  workspacePath?: string;
  openApiUrl?: string;
  /** A repository-relative OpenAPI file, as an alternative to `openApiUrl` — what "Inspect
   *  project" fills in rather than a typed address. */
  openApiFile?: string;
  /** An execution target approved from a discovery scan. */
  applyEnvironment?: ProposedEnvironment;
  /** A Compose file approved from a discovery scan as the Local Lab file. */
  applyLocalLabComposeFile?: string;
}

/** What came of an optional OpenAPI import attempted in the same act as creation. */
export interface ImportOutcome {
  attempted: boolean;
  succeeded: boolean;
  message: string | null;
  info: string | null;
  error: string | null;
  errorDetails: string[];
}

/**
 * @param setupWarning non-fatal: the service was created, but Vortex could not apply an approved
 *                      discovery selection (the environment or Local Lab file). Rare — both were
 *                      already validated once, at scan time.
 */
export interface CreateServiceResponse {
  service: ServiceListItem;
  importOutcome: ImportOutcome;
  setupWarning: string | null;
}

export function useCreateServiceMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: CreateServiceRequest) =>
      apiClient.post<CreateServiceResponse>('/api/services', request),
    onSuccess: () => {
      // Refreshes the services list, the topbar's switcher (same query key), and the homepage.
      queryClient.invalidateQueries({ queryKey: ['services'] });
      queryClient.invalidateQueries({ queryKey: ['home'] });
    },
  });
}

/**
 * What Vortex finds in an OpenAPI address, and what it can already tell about a repository path —
 * shown live on the "Add service" form, before either is committed by an actual create. Both
 * preview endpoints always return 200; branch on `ok`/`exists`, not on request failure.
 */
export interface OpenApiPreviewRequest {
  url: string;
}

export interface OperationPreview {
  label: string;
}

export interface OpenApiPreviewResponse {
  ok: boolean;
  title: string | null;
  operationCount: number;
  sample: OperationPreview[];
  error: string | null;
  errorDetails: string[];
}

export function useDeleteServiceMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (serviceId: string) => apiClient.delete<void>(`/api/services/${serviceId}`),
    onSuccess: () => {
      // Refreshes the services list, the topbar's switcher (same query key), and the homepage.
      queryClient.invalidateQueries({ queryKey: ['services'] });
      queryClient.invalidateQueries({ queryKey: ['home'] });
    },
  });
}

export function useOpenApiPreviewMutation() {
  return useMutation({
    mutationFn: (request: OpenApiPreviewRequest) =>
      apiClient.post<OpenApiPreviewResponse>('/api/services/openapi-preview', request),
  });
}

export interface WorkspaceCheckRequest {
  path: string;
}

export interface WorkspaceCheckResponse {
  exists: boolean;
  isDirectory: boolean;
  writable: boolean;
  gitRepository: boolean;
  error: string | null;
}

export function useWorkspaceCheckMutation() {
  return useMutation({
    mutationFn: (request: WorkspaceCheckRequest) =>
      apiClient.post<WorkspaceCheckResponse>('/api/services/workspace-check', request),
  });
}

/**
 * A directory listing for the "Add service" folder picker. A browser cannot turn a native file
 * dialog's pick into an absolute filesystem path, so browsing goes through Vortex's own backend,
 * which already has full filesystem access on this machine.
 */
export interface BrowseDirectoryRequest {
  path: string;
}

export interface DirectoryEntry {
  name: string;
  path: string;
}

export interface BrowseDirectoryResponse {
  path: string | null;
  parentPath: string | null;
  entries: DirectoryEntry[];
  error: string | null;
}

export function useBrowseDirectoryMutation() {
  return useMutation({
    mutationFn: (request: BrowseDirectoryRequest) =>
      apiClient.post<BrowseDirectoryResponse>('/api/services/browse-directory', request),
  });
}

/**
 * What a repository already committed to Vortex — the "Add service" form's evidence that a
 * `vortex.yaml` was found, and what it would restore. Also always 200; branch on `alreadyOnboarded`
 * / `found` / `valid`, not on request failure.
 */
export interface DetectConfigRequest {
  path: string;
}

export interface ConfigSummary {
  serviceName: string;
  serviceDescription: string;
  workloadCount: number;
  workloadNames: string[];
  environmentCount: number;
  operationBindingCount: number;
  hasProductionObservation: boolean;
  hasLocalLab: boolean;
  openApiSourceDescription: string | null;
}

export interface DetectConfigResponse {
  alreadyOnboarded: boolean;
  existingService: ServiceListItem | null;
  found: boolean;
  valid: boolean;
  summary: ConfigSummary | null;
  problems: string[];
  rawYaml: string | null;
  sourcePath: string | null;
}

export function useDetectConfigMutation() {
  return useMutation({
    mutationFn: (request: DetectConfigRequest) =>
      apiClient.post<DetectConfigResponse>('/api/services/detect-config', request),
  });
}

export interface AdoptServiceRequest {
  workspacePath: string;
  name: string;
}

export function useAdoptServiceMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: AdoptServiceRequest) =>
      apiClient.post<CreateServiceResponse>('/api/services/adopt', request),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['services'] });
      queryClient.invalidateQueries({ queryKey: ['home'] });
    },
  });
}
