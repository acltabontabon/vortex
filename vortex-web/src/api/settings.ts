// Field-for-field against dev.vortex.app.web.SettingsApiController.

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

export interface Settings {
  vortexVersion: string;
  engine: EngineSettings;
  engineAvailability: EngineAvailability;
  aiSettings: AiSettings;
  aiAvailability: AiAvailability;
  installedModels: string[];
  labStatus: LabStatus;
  workspacePath: string;
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
