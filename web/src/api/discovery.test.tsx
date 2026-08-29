import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import {
  useApplyServiceDiscoveryMutation,
  useDiscoveryScanMutation,
  useServiceDiscoveryScanMutation,
} from './discovery';

function withQueryClient(queryClient: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('useDiscoveryScanMutation', () => {
  it('posts the chosen path to the onboarding scan endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ ok: true, findings: [], conflicts: [], partialFailures: [] }), {
        status: 200,
      }),
    );
    vi.stubGlobal('fetch', fetchMock);
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    const { result } = renderHook(() => useDiscoveryScanMutation(), {
      wrapper: withQueryClient(queryClient),
    });

    await act(async () => {
      result.current.mutate({ path: '/tmp/checkout' });
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/services/discovery-scan'),
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ path: '/tmp/checkout' }) }),
    );
  });
});

describe('useServiceDiscoveryScanMutation', () => {
  it('scopes the scan to the given service', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ ok: true, findings: [], conflicts: [], partialFailures: [] }), {
        status: 200,
      }),
    );
    vi.stubGlobal('fetch', fetchMock);
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    const { result } = renderHook(() => useServiceDiscoveryScanMutation('svc-1'), {
      wrapper: withQueryClient(queryClient),
    });

    await act(async () => {
      result.current.mutate();
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/services/svc-1/discovery/scan'),
      expect.objectContaining({ method: 'POST' }),
    );
  });
});

describe('useApplyServiceDiscoveryMutation', () => {
  it('invalidates everything cached about the service once applied', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(new Response(JSON.stringify({ message: 'Discovered setup applied.' }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    queryClient.setQueryData(['service', 'svc-1'], { id: 'svc-1' });

    const { result } = renderHook(() => useApplyServiceDiscoveryMutation('svc-1'), {
      wrapper: withQueryClient(queryClient),
    });

    await act(async () => {
      result.current.mutate({ applyOpenApiSource: false, applyEnvironment: false, applyLocalLab: true, localLabComposeFile: 'compose.yaml' });
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/services/svc-1/discovery/apply'),
      expect.objectContaining({ method: 'POST' }),
    );
    expect(queryClient.getQueryState(['service', 'svc-1'])?.isInvalidated).toBe(true);
  });
});
