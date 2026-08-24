import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { useRecommendationQuery, type RecommendationDto } from './tests';

function withQueryClient(queryClient: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

const RECOMMENDATION: RecommendationDto = {
  type: 'SMOKE',
  model: 'OPEN',
  shapeKind: 'STEADY',
  purpose: 'A very small, steady check.',
  headline: '10 requests/sec for 30s',
  startLevel: 10,
  durationMinutes: 1,
  explicitStages: [],
  productionInformed: false,
  safetyCeilingApplied: false,
  sourceDescription: 'Manually entered',
  derivation: null,
  availableShapeKinds: ['STEADY'],
};

describe('useRecommendationQuery', () => {
  it('requests the recommendation for the given type and model', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(new Response(JSON.stringify(RECOMMENDATION), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    const { result } = renderHook(() => useRecommendationQuery('checkout', 'SMOKE', 'OPEN'), {
      wrapper: withQueryClient(queryClient),
    });

    await waitFor(() => expect(result.current.data).toEqual(RECOMMENDATION));
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/services/checkout/tests/recommendation?type=SMOKE&model=OPEN'),
      expect.anything(),
    );
  });

  it('keys the query on service, type and model, so switching Intent fetches fresh', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(new Response(JSON.stringify(RECOMMENDATION), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    renderHook(() => useRecommendationQuery('checkout', 'SMOKE', 'OPEN'), {
      wrapper: withQueryClient(queryClient),
    });
    renderHook(() => useRecommendationQuery('checkout', 'STRESS', 'OPEN'), {
      wrapper: withQueryClient(queryClient),
    });

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
  });

  it('does not fetch while disabled', () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    renderHook(() => useRecommendationQuery('checkout', 'SMOKE', 'OPEN', false), {
      wrapper: withQueryClient(queryClient),
    });

    expect(fetchMock).not.toHaveBeenCalled();
  });
});
