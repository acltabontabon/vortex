// Field-for-field against dev.vortex.app.web.ServicesApiController.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from './client';

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

export interface CreateServiceResponse {
  service: ServiceListItem;
  importOutcome: ImportOutcome;
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
