// Field-for-field against com.acltabontabon.vortex.app.web.SettingsApiController.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from './client';

export interface EngineSettings {
  usesDocker: boolean;
  runner: string;
  executable: string;
  dockerImage: string;
  compressRawMetrics: boolean;
}

export interface EngineAvailability {
  available: boolean;
  version: string;
  problem: string;
  remedy: string;
}

export interface AiSettings {
  provider: string;
  baseUrl: string;
  model: string;
}

export interface AiAvailability {
  available: boolean;
  provider: string;
  model: string;
  problem: string;
  remedy: string;
}

export interface LabStatus {
  dockerAvailable: boolean;
  daemonRunning: boolean;
  composeAvailable: boolean;
  usable: boolean;
  version: string;
  remedy: string;
}

export interface ResourceEnvelope {
  cpuMillicores: number | null;
  memoryBytes: number | null;
}

export interface HostShape {
  operatingSystem: string;
  osVersion: string;
  architecture: string;
  availableProcessors: number;
  totalMemoryBytes: number;
}

/** What a budget resolves to right now, on this host. */
export interface ResolvedLoadGeneratorBudget {
  mode: 'automatic' | 'custom';
  allocation: ResourceEnvelope;
  detectedHost: HostShape;
  osAndVortexReserve: ResourceEnvelope;
  sutReserve: ResourceEnvelope;
  colocatedWithManagedSut: boolean;
}

/** As saved — cpuMillicores/memoryMebibytes are only meaningful when mode is 'custom'. */
export interface ConfiguredLoadGeneratorBudget {
  mode: 'automatic' | 'custom';
  cpuMillicores: number | null;
  memoryMebibytes: number | null;
}

/**
 * Three distinct things, never conflated: `configured` is what was saved; `effective` is what
 * actually applies right now given `configured.mode`; `automaticPreview` is always what Automatic
 * would currently choose, regardless of the saved mode, so a Custom user can see what switching back
 * would give them without `effective` ever silently overriding their saved values.
 */
export interface LoadGeneratorSettings {
  configured: ConfiguredLoadGeneratorBudget;
  effective: ResolvedLoadGeneratorBudget;
  automaticPreview: ResolvedLoadGeneratorBudget;
}

export interface Settings {
  vortexVersion: string;
  engine: EngineSettings;
  engineAvailability: EngineAvailability;
  aiSettings: AiSettings;
  aiAvailability: AiAvailability;
  installedModels: string[];
  labStatus: LabStatus;
  workspacePath: string;
  loadGenerator: LoadGeneratorSettings;
}

export function useSettingsQuery() {
  return useQuery({
    queryKey: ['settings'],
    queryFn: () => apiClient.get<Settings>('/api/settings'),
  });
}

export interface RetryAiResponse {
  availability: AiAvailability;
  message: string;
  succeeded: boolean;
}

export function useRetryAiMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => apiClient.post<RetryAiResponse>('/api/settings/ai/retry'),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['settings'] }),
  });
}

export interface ChooseModelResponse {
  message: string;
}

export function useChooseModelMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (model: string) =>
      apiClient.post<ChooseModelResponse>('/api/settings/ai/model', { model }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['settings'] }),
  });
}

export interface ChooseLoadGeneratorBudgetRequest {
  mode: 'automatic' | 'custom';
  cpuMillicores?: number;
  memoryMebibytes?: number;
}

export interface ChooseLoadGeneratorBudgetResponse {
  message: string;
}

export function useChooseLoadGeneratorBudgetMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: ChooseLoadGeneratorBudgetRequest) =>
      apiClient.post<ChooseLoadGeneratorBudgetResponse>('/api/settings/load-generator', request),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['settings'] }),
  });
}
