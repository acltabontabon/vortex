import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useRunProgress, type RunProgress } from './runs';

const SAMPLE: RunProgress = {
  state: 'RUNNING',
  elapsed: '30s',
  stage: 'ramp-up',
  percent: 40,
  targetRate: '100 req/s',
  currentRate: '95 req/s',
  p95: '120ms',
  errorRate: '0%',
  message: '',
};

/**
 * jsdom does not implement `EventSource` at all. This captures every instance the hook
 * constructs and lets a test drive its listeners directly, the same way `test/setup.ts` stubs
 * `ResizeObserver` for Mantine.
 */
class MockEventSource {
  static instances: MockEventSource[] = [];

  closed = false;
  onerror: (() => void) | null = null;
  url: string;
  private listeners = new Map<string, ((event: MessageEvent) => void)[]>();

  constructor(url: string) {
    this.url = url;
    MockEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: (event: MessageEvent) => void) {
    const forType = this.listeners.get(type) ?? [];
    forType.push(listener);
    this.listeners.set(type, forType);
  }

  close() {
    this.closed = true;
  }

  emit(type: string, data?: unknown) {
    const event = { data: JSON.stringify(data) } as MessageEvent;
    for (const listener of this.listeners.get(type) ?? []) {
      listener(event);
    }
  }

  triggerError() {
    this.onerror?.();
  }
}

function latestSource(): MockEventSource {
  return MockEventSource.instances[MockEventSource.instances.length - 1];
}

beforeEach(() => {
  MockEventSource.instances = [];
  vi.stubGlobal('EventSource', MockEventSource);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('useRunProgress', () => {
  it('updates from a progress event', () => {
    const { result } = renderHook(() =>
      useRunProgress('exec-1', { enabled: true, onFinished: vi.fn() }),
    );

    act(() => latestSource().emit('progress', SAMPLE));

    expect(result.current).toEqual(SAMPLE);
  });

  it('closes the source and reports finished on a finished event', () => {
    const onFinished = vi.fn();
    renderHook(() => useRunProgress('exec-1', { enabled: true, onFinished }));
    const source = latestSource();

    act(() => source.emit('finished'));

    expect(source.closed).toBe(true);
    expect(onFinished).toHaveBeenCalledTimes(1);
  });

  it('falls back to polling only after several consecutive errors, not the first one', () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ running: true, progress: SAMPLE }), { status: 200 }),
    ));
    renderHook(() => useRunProgress('exec-1', { enabled: true, onFinished: vi.fn() }));
    const source = latestSource();

    // The browser's own EventSource reconnect is trusted through a few failures.
    act(() => source.triggerError());
    act(() => source.triggerError());
    act(() => source.triggerError());
    expect(source.closed).toBe(false);

    act(() => source.triggerError());
    expect(source.closed).toBe(true);
  });

  it('polls for progress once it has fallen back, and stops once the run is no longer running', async () => {
    vi.useFakeTimers();
    const onFinished = vi.fn();
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ running: true, progress: SAMPLE }), { status: 200 }),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ running: false, progress: null }), { status: 200 }),
      );
    vi.stubGlobal('fetch', fetchMock);

    renderHook(() => useRunProgress('exec-1', { enabled: true, onFinished }));
    const source = latestSource();
    for (let i = 0; i < 4; i++) {
      await act(async () => source.triggerError());
    }
    expect(source.closed).toBe(true);

    await act(async () => vi.advanceTimersByTimeAsync(3000));
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(onFinished).not.toHaveBeenCalled();

    await act(async () => vi.advanceTimersByTimeAsync(3000));
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(onFinished).toHaveBeenCalledTimes(1);
  });

  it('closes the source and stops polling on unmount', () => {
    const { unmount } = renderHook(() =>
      useRunProgress('exec-1', { enabled: true, onFinished: vi.fn() }),
    );
    const source = latestSource();

    unmount();

    expect(source.closed).toBe(true);
  });

  it('does nothing when disabled', () => {
    renderHook(() => useRunProgress('exec-1', { enabled: false, onFinished: vi.fn() }));

    expect(MockEventSource.instances).toHaveLength(0);
  });
});
