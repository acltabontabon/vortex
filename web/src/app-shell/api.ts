import { useQuery } from '@tanstack/react-query';
import { apiClient } from '../api/client';

// Mirrors com.acltabontabon.vortex.app.web.RuntimeApiController.CheckDto
export interface RuntimeCheck {
  name: string;
  required: boolean;
  ok: boolean;
  mark: string;
  detail: string;
  remedy: string;
}

// Mirrors com.acltabontabon.vortex.app.web.RuntimeApiController.RuntimeSummaryDto
export interface RuntimeSummary {
  checks: RuntimeCheck[];
  satisfied: number;
  total: number;
  requirementsMet: boolean;
}

// Mirrors com.acltabontabon.vortex.app.web.ServicesApiController.ServiceSummaryDto
export interface ServiceSummary {
  id: string;
  name: string;
}

// 30s matches RuntimeStatus's own server-side cache TTL — refetching faster than that would just
// hit the same memoised value every time.
export function useRuntimeQuery() {
  return useQuery({
    queryKey: ['runtime'],
    queryFn: () => apiClient.get<RuntimeSummary>('/api/runtime'),
    refetchInterval: 30_000,
  });
}

/**
 * Recomputed on arrival rather than served from the cache {@link useRuntimeQuery} reads: someone
 * who navigates to the Runtime page has usually just installed something and wants to know
 * whether it worked, not whatever was true up to 30s ago.
 */
export function useRuntimeRefreshQuery() {
  return useQuery({
    queryKey: ['runtime', 'refresh'],
    queryFn: () => apiClient.post<RuntimeSummary>('/api/runtime/refresh'),
  });
}

export function useServicesQuery() {
  return useQuery({
    queryKey: ['services'],
    queryFn: () => apiClient.get<ServiceSummary[]>('/api/services'),
  });
}

// Mirrors com.acltabontabon.vortex.app.web.PaletteController.Entry
export interface PaletteEntry {
  kind: string;
  label: string;
  detail: string;
  href: string;
}

// The whole index, fetched once and filtered client-side — the command palette's own design
// intent (PaletteController returns everything unfiltered on purpose). A long staleTime is
// appropriate: this list changes only when services/workloads/runs change, not per keystroke.
export function usePaletteQuery(enabled: boolean) {
  return useQuery({
    queryKey: ['palette'],
    queryFn: () => apiClient.get<PaletteEntry[]>('/palette.json'),
    staleTime: 60_000,
    enabled,
  });
}
