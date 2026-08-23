import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { usePreflightQuery, useStartRunMutation, useCancelRunMutation } from './run';

function withQueryClient(queryClient: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('usePreflightQuery', () => {
  it('never fetches when no workload is selected, so a closed drawer costs nothing', () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    renderHook(() => usePreflightQuery('svc-1', null, 'local', null), {
      wrapper: withQueryClient(queryClient),
    });

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('fetches once a workload is selected', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ canRun: true }), { status: 200 }),
    );
    vi.stubGlobal('fetch', fetchMock);
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    const { result } = renderHook(
      () => usePreflightQuery('svc-1', 'submission-load', 'local', null),
      { wrapper: withQueryClient(queryClient) },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/services/svc-1/preflight?workload=submission-load&environment=local',
      expect.anything(),
    );
  });
});

describe('useStartRunMutation', () => {
  it('invalidates the whole service on success, not just the run', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ started: true, executionId: 'exec-1' }), { status: 200 }),
      ),
    );
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');

    const { result } = renderHook(() => useStartRunMutation('svc-1'), {
      wrapper: withQueryClient(queryClient),
    });
    await act(async () => {
      await result.current.mutateAsync({ workload: 'submission-load', environment: 'local' });
    });

    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['service', 'svc-1'] });
  });

  it('surfaces the server\'s own detail when a run is refused', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ detail: 'This target requires --confirm.' }), {
          status: 400,
        }),
      ),
    );
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    const { result } = renderHook(() => useStartRunMutation('svc-1'), {
      wrapper: withQueryClient(queryClient),
    });
    act(() => {
      result.current.mutate({ workload: 'submission-load', environment: 'local' });
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error).toMatchObject({ detail: 'This target requires --confirm.' });
  });
});

describe('useCancelRunMutation', () => {
  it('invalidates only this run, not the whole service', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ cancelled: true, message: 'Cancelled' }), { status: 200 }),
      ),
    );
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');

    const { result } = renderHook(() => useCancelRunMutation('exec-1'), {
      wrapper: withQueryClient(queryClient),
    });
    await act(async () => {
      await result.current.mutateAsync();
    });

    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['run', 'exec-1'] });
    expect(invalidate).not.toHaveBeenCalledWith({ queryKey: ['service', expect.anything()] });
  });
});
