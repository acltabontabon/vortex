// Field-for-field against com.acltabontabon.vortex.app.web.DiscoveryApiController and the
// discovery-scan/create endpoints on com.acltabontabon.vortex.app.web.ServicesApiController.

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { apiClient } from './client';
import { invalidateService } from './workspace';

export type Confidence = 'HIGH' | 'MEDIUM' | 'LOW';

export interface Finding {
  kind: string;
  label: string;
  sourceFile: string;
  evidence: string[];
  confidence: Confidence;
  confidenceExplanation: string;
  attributes: Record<string, string>;
}

export type ConflictField = 'OPENAPI_SOURCE' | 'EXECUTION_TARGET' | 'LOCAL_LAB';

export interface DiscoveryConflict {
  field: ConflictField;
  existingDescription: string;
  discoveredDescription: string;
}

/** {@code v1} only ever proposes a Compose-attached target — see {@code ProjectDiscoveryService}. */
export interface ProposedEnvironment {
  name: string;
  type: string;
  targetKind: string;
  targetSummary: string;
  composeFile: string | null;
  composeService: string | null;
  containerPort: number | null;
  dependencyMode: string;
}

/**
 * A Project Discovery scan's result — always {@code ok: true} unless the service has no project
 * directory recorded (or, for the onboarding path, no path was given). Nothing here has been
 * applied; every proposed field is a suggestion the reviewer selects into an apply request.
 */
export interface DiscoveryScanResponse {
  ok: boolean;
  error: string | null;
  proposedServiceName: string | null;
  proposedServiceDescription: string | null;
  proposedOpenApiSourceFile: string | null;
  proposedEnvironment: ProposedEnvironment | null;
  proposedLocalLabComposeFile: string | null;
  findings: Finding[];
  conflicts: DiscoveryConflict[];
  partialFailures: string[];
}

/** Scans a candidate directory before any service exists — the onboarding "Inspect project" path. */
export function useDiscoveryScanMutation() {
  return useMutation({
    mutationFn: (request: { path: string }) =>
      apiClient.post<DiscoveryScanResponse>('/api/services/discovery-scan', request),
  });
}

/** Re-scans an already-created service's own project directory — "Discover from project". */
export function useServiceDiscoveryScanMutation(serviceId: string) {
  return useMutation({
    mutationFn: () =>
      apiClient.post<DiscoveryScanResponse>(`/api/services/${serviceId}/discovery/scan`, {}),
  });
}

export interface ApplyDiscoveryRequest {
  applyOpenApiSource: boolean;
  openApiSourceFile?: string;
  applyEnvironment: boolean;
  environment?: ProposedEnvironment;
  applyLocalLab: boolean;
  localLabComposeFile?: string;
}

export function useApplyServiceDiscoveryMutation(serviceId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: ApplyDiscoveryRequest) =>
      apiClient.post<{ message: string }>(`/api/services/${serviceId}/discovery/apply`, request),
    onSuccess: () => invalidateService(queryClient, serviceId),
  });
}
