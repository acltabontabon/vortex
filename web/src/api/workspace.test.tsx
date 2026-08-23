import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { invalidateService, useServiceHeaderQuery } from './workspace';

function withQueryClient(queryClient: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('invalidateService', () => {
  it('invalidates every cached query for one service, and no other service\'s', () => {
    // Every query key in this module (and configuration.ts's) starts with ['service', id, ...] —
    // this is the one test that actually proves that prefix-invalidation convention holds, rather
    // than it just being documented in this function's own comment.
    const queryClient = new QueryClient();
    queryClient.setQueryData(['service', 'a'], { id: 'a' });
    queryClient.setQueryData(['service', 'a', 'overview'], { header: {} });
    queryClient.setQueryData(['service', 'a', 'tests'], { tests: [] });
    queryClient.setQueryData(['service', 'b'], { id: 'b' });

    invalidateService(queryClient, 'a');

    const invalidated = (key: unknown[]) => queryClient.getQueryState(key)?.isInvalidated === true;
    expect(invalidated(['service', 'a'])).toBe(true);
    expect(invalidated(['service', 'a', 'overview'])).toBe(true);
    expect(invalidated(['service', 'a', 'tests'])).toBe(true);
    expect(invalidated(['service', 'b'])).toBe(false);
  });
});

describe('useServiceHeaderQuery', () => {
  it('polls every 2s while a run is in flight, and stops the moment it is not', async () => {
    vi.useFakeTimers();
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ running: { id: 'exec-1' } }), { status: 200 }),
      )
      .mockResolvedValueOnce(new Response(JSON.stringify({ running: null }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    renderHook(() => useServiceHeaderQuery('svc-1'), { wrapper: withQueryClient(queryClient) });

    // waitFor's own internal polling uses a timer too, which fake timers would freeze — flushing
    // pending microtasks directly is what actually surfaces the query's initial fetch.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);

    // The second response reported no run in flight, so nothing should schedule a third fetch —
    // this is the assertion that pins "stops polling once the run ends", not just "polls".
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});
