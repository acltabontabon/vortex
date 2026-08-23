import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createCommentaryBag, RUNNING_COMMENTARY_LINES, useRunningCommentary } from './runningCommentary';

/**
 * The bag mechanics are pure and checked without any fake timers — rotation *timing* is the hook's
 * concern, not the bag's, and the brief is explicit that timing itself should stay untested here.
 */
describe('the commentary bag', () => {
  it('never draws the same line twice in a row, across many reshuffles', () => {
    const bag = createCommentaryBag(RUNNING_COMMENTARY_LINES.length);
    let previous = bag.next();

    for (let i = 0; i < 500; i++) {
      const next = bag.next();
      expect(next).not.toBe(previous);
      previous = next;
    }
  });

  it('draws every line exactly once before any line repeats', () => {
    const length = RUNNING_COMMENTARY_LINES.length;
    const bag = createCommentaryBag(length);

    const firstPass = new Set<number>();
    for (let i = 0; i < length; i++) {
      firstPass.add(bag.next());
    }

    expect(firstPass.size).toBe(length);
  });
});

describe('the running commentary hook', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('starts on a line from the curated pool', () => {
    const { result } = renderHook(() => useRunningCommentary());

    expect(RUNNING_COMMENTARY_LINES).toContain(result.current);
  });

  it('rotates to a different line once the maximum interval has passed — never asserting the exact delay', () => {
    const { result } = renderHook(() => useRunningCommentary());
    const initial = result.current;

    act(() => {
      // The interval is randomized within [8s, 15s) per cycle; 16s guarantees at least one tick
      // fired without pinning this test to any particular cadence.
      vi.advanceTimersByTime(16_000);
    });

    expect(result.current).not.toBe(initial);
    expect(RUNNING_COMMENTARY_LINES).toContain(result.current);
  });
});
